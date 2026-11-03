//! Desktop smoke render:
//!   cargo run --release --example render -- <data_dir> <in> <out.png> [film] [paper] [params_json] [max_edge]
//! Inputs with a .dng/.nef/.cr3/... extension go through the RAW path.
//! Writes original | simulated side by side.
use spektrafilm_jni::engine::Engine;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (data, input, output) = (&a[1], &a[2], &a[3]);
    let film = a.get(4).map(String::as_str).unwrap_or("kodak_portra_400");
    let paper = a.get(5).map(String::as_str).unwrap_or("");
    let params = a.get(6).map(String::as_str).unwrap_or("");
    let max_edge: u32 = a.get(7).and_then(|s| s.parse().ok()).unwrap_or(0);
    let engine = Engine::new(data, false);
    let ext = input.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    if matches!(ext.as_str(), "dng" | "nef" | "cr2" | "cr3" | "arw" | "raf" | "orf" | "rw2") {
        let bytes = std::fs::read(input).expect("read raw");
        engine.set_source_raw(&bytes, 0).expect("raw decode");
    } else {
        let img = image::open(input).expect("open input").to_rgba8();
        let (w, h) = img.dimensions();
        engine.set_source_rgba8(img.as_raw(), w, h, w as usize * 4).unwrap();
    }
    let orig = engine.render_original(max_edge).expect("original");
    let out = engine.render(film, paper, params, max_edge, false, true).expect("render");
    let (w, h) = (out.width, out.height);
    eprintln!("{film}/{paper}: {w}x{h} in {} ms on {}", out.report.millis, out.report.backend);
    let mut canvas = image::RgbaImage::new(w * 2, h);
    image::imageops::replace(&mut canvas, &image::RgbaImage::from_raw(w, h, orig.rgba).unwrap(), 0, 0);
    image::imageops::replace(&mut canvas, &image::RgbaImage::from_raw(w, h, out.rgba).unwrap(), w as i64, 0);
    canvas.save(output).expect("save");
}
