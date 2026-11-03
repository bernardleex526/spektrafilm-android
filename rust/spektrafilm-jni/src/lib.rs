//! JNI bridge for `com.spektrafilm.app.NativeBridge`.
//!
//! All entry points convert errors into Java `RuntimeException`s and catch
//! panics at the boundary so the JVM is never aborted.

pub mod engine;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex};

use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jintArray, jstring};

use engine::Engine;

static ENGINE: Mutex<Option<Arc<Engine>>> = Mutex::new(None);

fn engine() -> Result<Arc<Engine>, String> {
    ENGINE
        .lock()
        .map_err(|_| "engine lock poisoned".to_string())?
        .clone()
        .ok_or_else(|| "NativeBridge.init() has not been called".to_string())
}

fn throw(env: &mut JNIEnv, msg: &str) {
    log::error!("{msg}");
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn guarded<T>(env: &mut JNIEnv, default: T, f: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(v)) => v,
        Ok(Err(msg)) => {
            throw(env, &msg);
            default
        }
        Err(_) => {
            throw(env, "native panic in spektrafilm");
            default
        }
    }
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(Into::into).map_err(|e| format!("reading jstring: {e}"))
}

fn new_jstring(env: &mut JNIEnv, s: impl AsRef<str>) -> Result<jstring, String> {
    env.new_string(s.as_ref()).map(|s| s.into_raw()).map_err(|e| e.to_string())
}

fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("spektrafilm"),
    );
}

/// `fun init(dataDir: String, tryGpu: Boolean): String` — returns backend name.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_init<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    data_dir: JString<'l>,
    try_gpu: jboolean,
) -> jstring {
    init_logging();
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let dir = jstr(env, &data_dir)?;
        let name = {
            let mut slot = ENGINE.lock().map_err(|_| "engine lock poisoned".to_string())?;
            if slot.is_none() {
                let e = Engine::new(dir, try_gpu != 0);
                log::info!("engine ready, backend = {}", e.backend_name());
                *slot = Some(Arc::new(e));
            }
            slot.as_ref().expect("just set").backend_name()
        };
        new_jstring(env, name)
    })
}

/// `fun listProfiles(): String` — JSON array of profile summaries.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_listProfiles<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let list = engine()?.list_profiles()?;
        let json = serde_json::to_string(&list).map_err(|e| e.to_string())?;
        new_jstring(env, json)
    })
}

/// `fun setSourceRaw(fd: Int, maxEdge: Int)` — takes ownership of `fd` and closes it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_setSourceRaw<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    fd: jint,
    max_edge: jint,
) {
    guarded(&mut env, (), |_env| {
        let bytes = read_fd(fd)?;
        engine()?.set_source_raw(&bytes, max_edge.max(0) as u32)?;
        Ok(())
    })
}

#[cfg(unix)]
fn read_fd(fd: jint) -> Result<Vec<u8>, String> {
    use std::io::Read;
    use std::os::fd::FromRawFd;
    if fd < 0 {
        return Err("invalid file descriptor".into());
    }
    // SAFETY: the Java side detached this fd and hands us exclusive ownership.
    let mut f = unsafe { std::fs::File::from_raw_fd(fd) };
    let mut buf = Vec::new();
    f.read_to_end(&mut buf).map_err(|e| format!("reading RAW file: {e}"))?;
    Ok(buf)
}

#[cfg(not(unix))]
fn read_fd(_fd: jint) -> Result<Vec<u8>, String> {
    Err("file descriptors are only supported on Android".into())
}

/// `fun outputDims(maxEdge: Int): IntArray` — [width, height] of a render at maxEdge.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_outputDims<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    max_edge: jint,
) -> jintArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let (w, h) = engine()?.output_dims(max_edge.max(0) as u32)?;
        let arr = env.new_int_array(2).map_err(|e| e.to_string())?;
        env.set_int_array_region(&arr, 0, &[w as i32, h as i32]).map_err(|e| e.to_string())?;
        Ok(arr.into_raw())
    })
}

/// `fun sourceKind(): String` — "srgb8", "raw", or "" when nothing is loaded.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_sourceKind<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let k = match engine()?.source_kind() {
            Some(engine::SourceKind::Srgb8) => "srgb8",
            Some(engine::SourceKind::Raw) => "raw",
            None => "",
        };
        new_jstring(env, k)
    })
}

#[cfg(target_os = "android")]
mod bitmap {
    //! Minimal libjnigraphics bindings: read/write ARGB_8888 bitmaps in place.
    use std::ffi::c_void;

    use jni::JNIEnv;
    use jni::objects::JObject;

    #[repr(C)]
    #[derive(Default)]
    struct AndroidBitmapInfo {
        width: u32,
        height: u32,
        stride: u32,
        format: i32,
        flags: u32,
    }

    const ANDROID_BITMAP_FORMAT_RGBA_8888: i32 = 1;

    #[link(name = "jnigraphics")]
    unsafe extern "C" {
        fn AndroidBitmap_getInfo(env: *mut jni::sys::JNIEnv, bmp: jni::sys::jobject, info: *mut AndroidBitmapInfo) -> i32;
        fn AndroidBitmap_lockPixels(env: *mut jni::sys::JNIEnv, bmp: jni::sys::jobject, addr: *mut *mut c_void) -> i32;
        fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bmp: jni::sys::jobject) -> i32;
    }

    /// Lock `bmp`, hand `(pixels, width, height, stride)` to `f`, then unlock.
    pub fn with_pixels<T>(
        env: &mut JNIEnv,
        bmp: &JObject,
        f: impl FnOnce(&mut [u8], u32, u32, usize) -> Result<T, String>,
    ) -> Result<T, String> {
        let raw_env = env.get_raw();
        let raw_bmp = bmp.as_raw();
        let mut info = AndroidBitmapInfo::default();
        // SAFETY: valid env/bitmap references for the duration of this JNI call.
        unsafe {
            if AndroidBitmap_getInfo(raw_env, raw_bmp, &mut info) != 0 {
                return Err("AndroidBitmap_getInfo failed".into());
            }
            if info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 {
                return Err(format!("bitmap must be ARGB_8888, got format {}", info.format));
            }
            let mut addr: *mut c_void = std::ptr::null_mut();
            if AndroidBitmap_lockPixels(raw_env, raw_bmp, &mut addr) != 0 || addr.is_null() {
                return Err("AndroidBitmap_lockPixels failed".into());
            }
            let len = info.stride as usize * info.height as usize;
            let pixels = std::slice::from_raw_parts_mut(addr as *mut u8, len);
            let r = f(pixels, info.width, info.height, info.stride as usize);
            AndroidBitmap_unlockPixels(raw_env, raw_bmp);
            r
        }
    }

    pub fn write_rgba(
        env: &mut JNIEnv,
        bmp: &JObject,
        rgba: &[u8],
        width: u32,
        height: u32,
    ) -> Result<(), String> {
        with_pixels(env, bmp, |px, w, h, stride| {
            if (w, h) != (width, height) {
                return Err(format!("bitmap is {w}x{h}, render is {width}x{height}"));
            }
            let row = width as usize * 4;
            for y in 0..height as usize {
                px[y * stride..y * stride + row].copy_from_slice(&rgba[y * row..(y + 1) * row]);
            }
            Ok(())
        })
    }
}

/// `fun setSourceBitmap(bitmap: Bitmap)` — copies an sRGB ARGB_8888 bitmap in as the source.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_setSourceBitmap<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    bmp: JObject<'l>,
) {
    guarded(&mut env, (), |env| {
        #[cfg(target_os = "android")]
        {
            let e = engine()?;
            bitmap::with_pixels(env, &bmp, |px, w, h, stride| e.set_source_rgba8(px, w, h, stride))?;
            Ok(())
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (env, bmp);
            Err("bitmaps are only supported on Android".into())
        }
    })
}

/// `fun render(film, paper, paramsJson, maxEdge, preferGpu, cachePipeline, out: Bitmap): String`
/// Renders into `out` (must be sized via outputDims(maxEdge)); returns a JSON report.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_render<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    film: JString<'l>,
    paper: JString<'l>,
    params_json: JString<'l>,
    max_edge: jint,
    prefer_gpu: jboolean,
    cache_pipeline: jboolean,
    out: JObject<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let film = jstr(env, &film)?;
        let paper = jstr(env, &paper)?;
        let params = jstr(env, &params_json)?;
        let r = engine()?.render(
            &film,
            &paper,
            &params,
            max_edge.max(0) as u32,
            prefer_gpu != 0,
            cache_pipeline != 0,
        )?;
        write_out(env, &out, &r)?;
        new_jstring(env, serde_json::to_string(&r.report).unwrap_or_default())
    })
}

/// `fun renderOriginal(maxEdge: Int, out: Bitmap)` — unprocessed source for comparison.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_spektrafilm_app_NativeBridge_renderOriginal<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    max_edge: jint,
    out: JObject<'l>,
) {
    guarded(&mut env, (), |env| {
        let r = engine()?.render_original(max_edge.max(0) as u32)?;
        write_out(env, &out, &r)
    })
}

fn write_out(env: &mut JNIEnv, out: &JObject, r: &engine::RenderOutput) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        bitmap::write_rgba(env, out, &r.rgba, r.width, r.height)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (env, out, r);
        Err("bitmaps are only supported on Android".into())
    }
}
