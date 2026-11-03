//! Platform-independent engine wrapped by the JNI layer.
//!
//! The engine owns the current *source image* in linear light (sRGB
//! primaries). Previews, thumbnails and exports are all rendered from it at a
//! requested long-edge size, so the Java side never ships full-size pixel
//! arrays back and forth. Kept free of JNI types so it can be unit-tested on
//! the desktop.

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use rayon::prelude::*;
use serde::Serialize;
use spektrafilm_core::params::RuntimeParams;
use spektrafilm_core::pipeline::Pipeline;
use spektrafilm_core::profile::{self, Profile};
use spektrafilm_gpu::ComputeBackend;
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::image::ImageBuf;
use spektrafilm_math::precision::{Scalar, from_f32, srgb_decode, srgb_encode, to_f32};

#[derive(Debug, Clone, Serialize)]
pub struct ProfileSummary {
    pub id: String,
    pub name: String,
    pub stage: String,
    pub film_type: String,
    pub support: String,
    pub channel_model: String,
    pub target_print: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ProcessReport {
    pub backend: String,
    pub millis: u128,
    pub gpu_fallback: bool,
    pub width: u32,
    pub height: u32,
}

pub struct RenderOutput {
    /// 8-bit sRGB, RGBA interleaved, alpha = 255.
    pub rgba: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub report: ProcessReport,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum SourceKind {
    Srgb8,
    Raw,
}

struct Source {
    full: Arc<ImageBuf>,
    kind: SourceKind,
    scaled: HashMap<u32, Arc<ImageBuf>>,
}

pub struct Engine {
    data_dir: PathBuf,
    gpu: Option<Box<dyn ComputeBackend>>,
    gpu_broken: AtomicBool,
    cache: Mutex<Option<(String, Arc<Pipeline>)>>,
    source: Mutex<Option<Source>>,
}

impl Engine {
    /// Create the engine. GPU initialisation is attempted once; any panic
    /// inside wgpu/driver setup is contained and leaves the engine CPU-only.
    pub fn new(data_dir: impl Into<PathBuf>, try_gpu: bool) -> Self {
        let gpu = if try_gpu {
            match catch_unwind(spektrafilm_gpu::wgpu_backend::WgpuBackend::new) {
                Ok(Some(b)) => Some(Box::new(b) as Box<dyn ComputeBackend>),
                Ok(None) => {
                    log::warn!("wgpu backend unavailable, using CPU");
                    None
                }
                Err(_) => {
                    log::error!("wgpu backend init panicked, using CPU");
                    None
                }
            }
        } else {
            None
        };
        Self {
            data_dir: data_dir.into(),
            gpu,
            gpu_broken: AtomicBool::new(false),
            cache: Mutex::new(None),
            source: Mutex::new(None),
        }
    }

    pub fn gpu_available(&self) -> bool {
        self.gpu.is_some() && !self.gpu_broken.load(Ordering::Relaxed)
    }

    pub fn backend_name(&self) -> String {
        match &self.gpu {
            Some(g) if !self.gpu_broken.load(Ordering::Relaxed) => g.name().to_string(),
            _ => CpuBackend.name().to_string(),
        }
    }

    pub fn list_profiles(&self) -> Result<Vec<ProfileSummary>, String> {
        let dir = self.data_dir.join("profiles");
        let mut ids: Vec<String> = std::fs::read_dir(&dir)
            .map_err(|e| format!("reading {}: {e}", dir.display()))?
            .filter_map(|e| e.ok())
            .filter_map(|e| {
                let p = e.path();
                (p.extension()? == "json").then(|| p.file_stem()?.to_str().map(str::to_owned))?
            })
            .collect();
        ids.sort();
        let mut out = Vec::with_capacity(ids.len());
        for id in ids {
            match profile::load_profile_by_name(&self.data_dir, &id) {
                Ok(p) => out.push(summarize(&id, &p)),
                Err(e) => log::warn!("skipping profile {id}: {e:?}"),
            }
        }
        Ok(out)
    }

    // ------------------------------------------------------------ sources

    /// Install an 8-bit sRGB RGBA image (row stride in bytes) as the source.
    pub fn set_source_rgba8(&self, rgba: &[u8], width: u32, height: u32, stride: usize) -> Result<(u32, u32), String> {
        let row = width as usize * 4;
        if width == 0 || height == 0 || stride < row || rgba.len() < stride * (height as usize - 1) + row {
            return Err(format!("bad image buffer {width}x{height} stride {stride} len {}", rgba.len()));
        }
        let lut: Vec<Scalar> = (0..256u32).map(|i| srgb_decode(from_f32(i as f32 / 255.0))).collect();
        let mut data = vec![from_f32(0.0); width as usize * height as usize * 3];
        data.par_chunks_mut(width as usize * 3).enumerate().for_each(|(y, out)| {
            let src = &rgba[y * stride..y * stride + row];
            for (o, px) in out.chunks_exact_mut(3).zip(src.chunks_exact(4)) {
                o[0] = lut[px[0] as usize];
                o[1] = lut[px[1] as usize];
                o[2] = lut[px[2] as usize];
            }
        });
        self.install(ImageBuf::from_data(width, height, data), SourceKind::Srgb8);
        Ok((width, height))
    }

    /// Decode a camera RAW/DNG file into linear sRGB-primaries light and
    /// install it as the source, downscaled so the long edge is at most
    /// `max_edge` (0 = keep full resolution).
    pub fn set_source_raw(&self, bytes: &[u8], max_edge: u32) -> Result<(u32, u32), String> {
        let img = decode_raw(bytes)?;
        let img = if max_edge > 0 { downscale_to(&img, max_edge) } else { img };
        let dims = (img.width, img.height);
        self.install(img, SourceKind::Raw);
        Ok(dims)
    }

    pub fn clear_source(&self) {
        if let Ok(mut s) = self.source.lock() {
            *s = None;
        }
    }

    fn install(&self, img: ImageBuf, kind: SourceKind) {
        if let Ok(mut s) = self.source.lock() {
            *s = Some(Source { full: Arc::new(img), kind, scaled: HashMap::new() });
        }
    }

    pub fn source_kind(&self) -> Option<SourceKind> {
        self.source.lock().ok()?.as_ref().map(|s| s.kind)
    }

    /// Dimensions the source would have when rendered at `max_edge`.
    pub fn output_dims(&self, max_edge: u32) -> Result<(u32, u32), String> {
        let s = self.source.lock().map_err(|_| "source lock poisoned".to_string())?;
        let src = s.as_ref().ok_or("no source image loaded")?;
        Ok(scaled_dims(src.full.width, src.full.height, max_edge))
    }

    fn source_at(&self, max_edge: u32) -> Result<Arc<ImageBuf>, String> {
        let mut s = self.source.lock().map_err(|_| "source lock poisoned".to_string())?;
        let src = s.as_mut().ok_or("no source image loaded")?;
        let (w, h) = scaled_dims(src.full.width, src.full.height, max_edge);
        if (w, h) == (src.full.width, src.full.height) {
            return Ok(Arc::clone(&src.full));
        }
        if let Some(img) = src.scaled.get(&max_edge) {
            return Ok(Arc::clone(img));
        }
        // Keep at most a few cached sizes (preview, thumbnail).
        if src.scaled.len() >= 3 {
            src.scaled.clear();
        }
        let img = Arc::new(downscale_to(&src.full, max_edge));
        src.scaled.insert(max_edge, Arc::clone(&img));
        Ok(img)
    }

    // ------------------------------------------------------------ rendering

    /// The unprocessed source, sRGB-encoded, for before/after comparison.
    pub fn render_original(&self, max_edge: u32) -> Result<RenderOutput, String> {
        let start = Instant::now();
        let img = self.source_at(max_edge)?;
        let rgba = linear_to_rgba(&img);
        Ok(RenderOutput {
            rgba,
            width: img.width,
            height: img.height,
            report: ProcessReport {
                backend: "none".into(),
                millis: start.elapsed().as_millis(),
                gpu_fallback: false,
                width: img.width,
                height: img.height,
            },
        })
    }

    /// Run the full spectral film/print/scan pipeline on the source at
    /// `max_edge` (0 = full resolution). `paper` may be empty: then the
    /// film's `target_print` is used (or the film itself for `scan_film`).
    /// `cache_pipeline = false` is used for thumbnails so they don't evict
    /// the pipeline the live preview is using.
    pub fn render(
        &self,
        film: &str,
        paper: &str,
        params_json: &str,
        max_edge: u32,
        prefer_gpu: bool,
        cache_pipeline: bool,
    ) -> Result<RenderOutput, String> {
        let start = Instant::now();
        let pipeline = if cache_pipeline {
            self.pipeline_for(film, paper, params_json)?
        } else {
            Arc::new(build_pipeline(&self.data_dir, film, paper, params_json)?)
        };
        let source = self.source_at(max_edge)?;
        let (width, height) = (source.width, source.height);

        let use_gpu = prefer_gpu && self.gpu_available();
        let (out, backend, fallback) = if use_gpu {
            let gpu = self.gpu.as_deref().expect("checked gpu_available");
            match catch_unwind(AssertUnwindSafe(|| pipeline.process((*source).clone(), gpu))) {
                Ok(img) => (img, gpu.name().to_string(), false),
                Err(_) => {
                    log::error!("GPU pipeline panicked; disabling GPU and retrying on CPU");
                    self.gpu_broken.store(true, Ordering::Relaxed);
                    (run_cpu(&pipeline, &source)?, CpuBackend.name().to_string(), true)
                }
            }
        } else {
            (run_cpu(&pipeline, &source)?, CpuBackend.name().to_string(), false)
        };
        drop(source);

        if out.width != width || out.height != height {
            return Err(format!(
                "pipeline changed image size {width}x{height} -> {}x{}",
                out.width, out.height
            ));
        }
        Ok(RenderOutput {
            rgba: encoded_to_rgba(&out),
            width,
            height,
            report: ProcessReport {
                backend,
                millis: start.elapsed().as_millis(),
                gpu_fallback: fallback,
                width,
                height,
            },
        })
    }

    fn pipeline_for(&self, film: &str, paper: &str, params_json: &str) -> Result<Arc<Pipeline>, String> {
        let key = format!("{film}\u{1}{paper}\u{1}{params_json}");
        let mut cache = self.cache.lock().map_err(|_| "pipeline cache poisoned".to_string())?;
        if let Some((k, p)) = cache.as_ref() {
            if *k == key {
                return Ok(Arc::clone(p));
            }
        }
        *cache = None;
        let pipeline = Arc::new(build_pipeline(&self.data_dir, film, paper, params_json)?);
        *cache = Some((key, Arc::clone(&pipeline)));
        Ok(pipeline)
    }
}

fn summarize(id: &str, p: &Profile) -> ProfileSummary {
    ProfileSummary {
        id: id.to_string(),
        name: p
            .info
            .name
            .clone()
            .or_else(|| p.info.stock.clone())
            .unwrap_or_else(|| id.to_string()),
        stage: p.info.stage.clone(),
        film_type: p.info.film_type.clone(),
        support: p.info.support.clone(),
        channel_model: p.info.channel_model.clone(),
        target_print: p.info.target_print.clone(),
    }
}

pub fn parse_params(params_json: &str) -> Result<RuntimeParams, String> {
    let mut params: RuntimeParams = if params_json.trim().is_empty() {
        RuntimeParams::default()
    } else {
        serde_json::from_str(params_json).map_err(|e| format!("invalid params JSON: {e}"))?
    };
    // Sources are always handed to the pipeline as linear sRGB primaries
    // (8-bit inputs are decoded on install) and rendered back to sRGB.
    params.io.input_color_space = "sRGB".to_string();
    params.io.input_cctf_decoding = false;
    params.io.output_color_space = "sRGB".to_string();
    params.io.output_cctf_encoding = true;
    params.io.upscale_factor = 1.0;
    params.io.crop = false;
    Ok(params)
}

fn build_pipeline(data_dir: &Path, film: &str, paper: &str, params_json: &str) -> Result<Pipeline, String> {
    let params = parse_params(params_json)?;
    let film_profile =
        profile::load_profile_by_name(data_dir, film).map_err(|e| format!("loading film {film}: {e:?}"))?;
    let print_id = if params.io.scan_film {
        film.to_string()
    } else if !paper.is_empty() {
        paper.to_string()
    } else if let Some(t) = &film_profile.info.target_print {
        t.clone()
    } else {
        return Err(format!("film {film} has no target_print; choose a paper or enable scan_film"));
    };
    let print_profile = profile::load_profile_by_name(data_dir, &print_id)
        .map_err(|e| format!("loading paper {print_id}: {e:?}"))?;
    catch_unwind(AssertUnwindSafe(|| {
        Pipeline::new_with_spectral(film_profile, print_profile, params, data_dir)
    }))
    .map_err(|_| "pipeline construction panicked".to_string())?
}

fn run_cpu(pipeline: &Pipeline, image: &ImageBuf) -> Result<ImageBuf, String> {
    catch_unwind(AssertUnwindSafe(|| pipeline.process(image.clone(), &CpuBackend)))
        .map_err(|_| "CPU pipeline panicked".to_string())
}

// ---------------------------------------------------------------- RAW

pub fn decode_raw(bytes: &[u8]) -> Result<ImageBuf, String> {
    use rawler::decoders::RawDecodeParams;
    use rawler::imgop::develop::{Intermediate, ProcessingStep, RawDevelop};
    use rawler::rawsource::RawSource;

    let src = RawSource::new_from_slice(bytes);
    let raw = catch_unwind(AssertUnwindSafe(|| rawler::decode(&src, &RawDecodeParams::default())))
        .map_err(|_| "RAW decoder panicked".to_string())?
        .map_err(|e| format!("unsupported or damaged RAW file: {e:?}"))?;
    let flips = raw.orientation.to_flips();
    let mut dev = RawDevelop::default();
    // Keep it linear: the film pipeline applies its own tone response.
    dev.steps.retain(|s| !matches!(s, ProcessingStep::SRgb));
    let inter = catch_unwind(AssertUnwindSafe(|| dev.develop_intermediate(&raw)))
        .map_err(|_| "RAW develop panicked".to_string())?
        .map_err(|e| format!("RAW develop failed: {e:?}"))?;
    drop(raw);
    let clamp = |v: f32| from_f32(if v.is_finite() { v.max(0.0) } else { 0.0 });
    let (w, h, data): (usize, usize, Vec<Scalar>) = match inter {
        Intermediate::Monochrome(p) => {
            (p.width, p.height, p.data.par_iter().flat_map_iter(|&v| [clamp(v); 3]).collect())
        }
        Intermediate::ThreeColor(p) => (
            p.width,
            p.height,
            p.data.par_iter().flat_map_iter(|px| [clamp(px[0]), clamp(px[1]), clamp(px[2])]).collect(),
        ),
        Intermediate::FourColor(p) => (
            p.width,
            p.height,
            p.data.par_iter().flat_map_iter(|px| [clamp(px[0]), clamp(px[1]), clamp(px[2])]).collect(),
        ),
    };
    Ok(apply_flips(ImageBuf::from_data(w as u32, h as u32, data), flips))
}

/// Apply rawler's `(transpose, hflip, vflip)` orientation: flips first, then transpose.
pub fn apply_flips(img: ImageBuf, (transpose, hflip, vflip): (bool, bool, bool)) -> ImageBuf {
    if !transpose && !hflip && !vflip {
        return img;
    }
    let (w, h) = (img.width as usize, img.height as usize);
    let (ow, oh) = if transpose { (h, w) } else { (w, h) };
    let mut out = vec![from_f32(0.0); w * h * 3];
    out.par_chunks_mut(ow * 3).enumerate().for_each(|(oy, row)| {
        for ox in 0..ow {
            // Destination (ox, oy) comes from the flipped image at (x, y).
            let (fx, fy) = if transpose { (oy, ox) } else { (ox, oy) };
            let sx = if hflip { w - 1 - fx } else { fx };
            let sy = if vflip { h - 1 - fy } else { fy };
            let s = (sy * w + sx) * 3;
            row[ox * 3..ox * 3 + 3].copy_from_slice(&img.data[s..s + 3]);
        }
    });
    ImageBuf::from_data(ow as u32, oh as u32, out)
}

// ---------------------------------------------------------------- resampling

pub fn scaled_dims(w: u32, h: u32, max_edge: u32) -> (u32, u32) {
    let long = w.max(h);
    if max_edge == 0 || long <= max_edge {
        return (w, h);
    }
    let s = max_edge as f64 / long as f64;
    (((w as f64 * s).round() as u32).max(1), ((h as f64 * s).round() as u32).max(1))
}

/// Area-average (box) downscale in linear light.
pub fn downscale_to(img: &ImageBuf, max_edge: u32) -> ImageBuf {
    let (ow, oh) = scaled_dims(img.width, img.height, max_edge);
    if (ow, oh) == (img.width, img.height) {
        return img.clone();
    }
    let (w, h) = (img.width as usize, img.height as usize);
    let (ow, oh) = (ow as usize, oh as usize);
    let xw = area_weights(w, ow);
    let yw = area_weights(h, oh);
    // Horizontal pass: h rows x ow columns (f32 accumulators).
    let mut tmp = vec![0f32; h * ow * 3];
    tmp.par_chunks_mut(ow * 3).enumerate().for_each(|(y, row)| {
        let src = &img.data[y * w * 3..(y + 1) * w * 3];
        for (ox, taps) in xw.iter().enumerate() {
            let mut acc = [0f32; 3];
            for &(sx, wt) in taps {
                for c in 0..3 {
                    acc[c] += to_f32(src[sx * 3 + c]) * wt;
                }
            }
            row[ox * 3..ox * 3 + 3].copy_from_slice(&acc);
        }
    });
    let mut out = vec![from_f32(0.0); oh * ow * 3];
    out.par_chunks_mut(ow * 3).enumerate().for_each(|(oy, row)| {
        for (ox, px) in row.chunks_exact_mut(3).enumerate() {
            let mut acc = [0f32; 3];
            for &(sy, wt) in &yw[oy] {
                let s = (sy * ow + ox) * 3;
                for c in 0..3 {
                    acc[c] += tmp[s + c] * wt;
                }
            }
            for c in 0..3 {
                px[c] = from_f32(acc[c]);
            }
        }
    });
    ImageBuf::from_data(ow as u32, oh as u32, out)
}

/// For each output index, the source indices and normalised coverage weights.
fn area_weights(n_in: usize, n_out: usize) -> Vec<Vec<(usize, f32)>> {
    let scale = n_in as f64 / n_out as f64;
    (0..n_out)
        .map(|o| {
            let a = o as f64 * scale;
            let b = ((o + 1) as f64 * scale).min(n_in as f64);
            let mut taps = Vec::with_capacity(scale.ceil() as usize + 1);
            let mut i = a.floor() as usize;
            while (i as f64) < b && i < n_in {
                let lo = a.max(i as f64);
                let hi = b.min((i + 1) as f64);
                if hi > lo {
                    taps.push((i, ((hi - lo) / (b - a)) as f32));
                }
                i += 1;
            }
            taps
        })
        .collect()
}

// ---------------------------------------------------------------- encoding

fn quantize(v: f32) -> u8 {
    let v = if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.0 };
    (v * 255.0).round_ties_even() as u8
}

/// Pipeline output is already sRGB-encoded.
fn encoded_to_rgba(img: &ImageBuf) -> Vec<u8> {
    let mut out = vec![255u8; img.data.len() / 3 * 4];
    out.par_chunks_mut(4).zip(img.data.par_chunks(3)).for_each(|(o, px)| {
        o[0] = quantize(to_f32(px[0]));
        o[1] = quantize(to_f32(px[1]));
        o[2] = quantize(to_f32(px[2]));
    });
    out
}

fn linear_to_rgba(img: &ImageBuf) -> Vec<u8> {
    let mut out = vec![255u8; img.data.len() / 3 * 4];
    out.par_chunks_mut(4).zip(img.data.par_chunks(3)).for_each(|(o, px)| {
        for c in 0..3 {
            let v = to_f32(px[c]).clamp(0.0, 1.0);
            o[c] = quantize(to_f32(srgb_encode(from_f32(v))));
        }
    });
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn data_dir() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../third_party/spektrafilm-rs/data")
    }

    fn test_image(w: u32, h: u32) -> Vec<u8> {
        let mut v = Vec::with_capacity((w * h * 4) as usize);
        for y in 0..h {
            for x in 0..w {
                v.push((x * 255 / (w - 1)) as u8);
                v.push((y * 255 / (h - 1)) as u8);
                v.push(((x + y) * 255 / (w + h - 2)) as u8);
                v.push(255);
            }
        }
        v
    }

    fn engine_with_source(w: u32, h: u32) -> Engine {
        let e = Engine::new(data_dir(), false);
        e.set_source_rgba8(&test_image(w, h), w, h, w as usize * 4).unwrap();
        e
    }

    #[test]
    fn lists_profiles() {
        let e = Engine::new(data_dir(), false);
        let p = e.list_profiles().unwrap();
        assert!(p.iter().any(|s| s.id == "kodak_portra_400"));
        assert!(p.iter().any(|s| s.stage == "printing"));
    }

    #[test]
    fn renders_on_cpu_at_full_and_reduced_size() {
        let e = engine_with_source(64, 48);
        let out = e.render("kodak_portra_400", "", "", 0, false, true).unwrap();
        assert_eq!((out.width, out.height), (64, 48));
        assert_eq!(out.rgba.len(), 64 * 48 * 4);
        let first = &out.rgba[0..3];
        assert!(out.rgba.chunks_exact(4).any(|p| &p[0..3] != first));
        assert!(out.rgba.chunks_exact(4).all(|p| p[3] == 255));

        assert_eq!(e.output_dims(32).unwrap(), (32, 24));
        let small = e.render("kodak_portra_400", "", "", 32, false, false).unwrap();
        assert_eq!((small.width, small.height), (32, 24));
    }

    #[test]
    fn render_without_source_errors() {
        let e = Engine::new(data_dir(), false);
        assert!(e.render("kodak_portra_400", "", "", 0, false, true).is_err());
        assert!(e.output_dims(100).is_err());
    }

    #[test]
    fn original_roundtrips_srgb() {
        let (w, h) = (16, 8);
        let src = test_image(w, h);
        let e = Engine::new(data_dir(), false);
        e.set_source_rgba8(&src, w, h, w as usize * 4).unwrap();
        let orig = e.render_original(0).unwrap();
        let max_err = orig.rgba.iter().zip(&src).map(|(a, b)| (*a as i32 - *b as i32).abs()).max().unwrap();
        assert!(max_err <= 1, "sRGB decode/encode roundtrip error {max_err}");
    }

    #[test]
    fn stride_is_respected() {
        let (w, h) = (4u32, 3u32);
        let stride = 24usize; // 16 bytes of pixels + 8 bytes padding per row
        let mut buf = vec![0u8; stride * h as usize];
        for y in 0..h as usize {
            for x in 0..w as usize {
                buf[y * stride + x * 4..y * stride + x * 4 + 4].copy_from_slice(&[255, 0, 0, 255]);
            }
            buf[y * stride + 16..y * stride + 24].fill(77); // padding garbage
        }
        let e = Engine::new(data_dir(), false);
        e.set_source_rgba8(&buf, w, h, stride).unwrap();
        let o = e.render_original(0).unwrap();
        assert!(o.rgba.chunks_exact(4).all(|p| p == [255, 0, 0, 255]));
    }

    #[test]
    fn downscale_preserves_mean_and_dims() {
        let (w, h) = (101u32, 37u32);
        let data: Vec<Scalar> = (0..w * h * 3).map(|i| from_f32((i % 17) as f32 / 16.0)).collect();
        let img = ImageBuf::from_data(w, h, data.clone());
        let small = downscale_to(&img, 40);
        assert_eq!((small.width, small.height), scaled_dims(w, h, 40));
        let mean = |d: &[Scalar]| d.iter().map(|&v| to_f32(v) as f64).sum::<f64>() / d.len() as f64;
        assert!((mean(&data) - mean(&small.data)).abs() < 0.01);
    }

    #[test]
    fn flips_match_exif_rotate90() {
        // 2x1 image [A B] rotated 90° clockwise becomes a 1x2 column [A; B].
        let img = ImageBuf::from_data(2, 1, vec![from_f32(1.0); 3].into_iter().chain(vec![from_f32(2.0); 3]).collect());
        let r = apply_flips(img, rawler::decoders::Orientation::Rotate90.to_flips());
        assert_eq!((r.width, r.height), (1, 2));
        assert_eq!(to_f32(r.data[0]), 1.0);
        assert_eq!(to_f32(r.data[3]), 2.0);
    }

    #[test]
    fn partial_params_json_overrides() {
        let p = parse_params(r#"{"camera":{"exposure_compensation_ev":1.5}}"#).unwrap();
        assert_eq!(p.camera.exposure_compensation_ev, 1.5);
        assert_eq!(p.io.input_color_space, "sRGB");
        assert!(parse_params("{not json").is_err());
    }

    #[test]
    fn garbage_raw_is_an_error_not_a_crash() {
        let e = Engine::new(data_dir(), false);
        assert!(e.set_source_raw(&[0u8; 1024], 0).is_err());
    }
}
