#[path = "src/config.rs"]
mod config;
use config::AppConfig;
use image::{imageops::{self, FilterType}, RgbaImage};
use std::{env, error::Error, fs, io::{self, Write}, path::{Path, PathBuf}};

fn fit_icon(source: &RgbaImage, size: u32) -> RgbaImage {
    let ratio = size as f64 / source.width().max(source.height()) as f64;
    let width = ((source.width() as f64 * ratio).round() as u32).clamp(1, size);
    let height = ((source.height() as f64 * ratio).round() as u32).clamp(1, size);
    let scaled = imageops::resize(source, width, height, FilterType::Lanczos3);
    let mut square = RgbaImage::new(size, size);
    imageops::replace(&mut square, &scaled, ((size - width) / 2) as i64, ((size - height) / 2) as i64);
    square
}

fn u16le(out: &mut Vec<u8>, n: u16) { out.extend(n.to_le_bytes()); }
fn u32le(out: &mut Vec<u8>, n: u32) { out.extend(n.to_le_bytes()); }

fn icon_frame(image: &RgbaImage) -> Vec<u8> {
    let n = image.width();
    let mask_stride = ((n + 31) / 32) * 4;
    let mut out = Vec::new();
    u32le(&mut out, 40); // BITMAPINFOHEADER
    u32le(&mut out, n);
    u32le(&mut out, n * 2); // XOR + AND heights
    u16le(&mut out, 1);
    u16le(&mut out, 32);
    u32le(&mut out, 0); // BI_RGB
    u32le(&mut out, n * n * 4 + mask_stride * n);
    for _ in 0..4 { u32le(&mut out, 0); }
    for y in (0..n).rev() {
        for x in 0..n {
            let [r, g, b, a] = image.get_pixel(x, y).0;
            out.extend([b, g, r, a]);
        }
    }
    for y in (0..n).rev() {
        let mut mask = vec![0u8; mask_stride as usize];
        for x in 0..n {
            if image.get_pixel(x, y).0[3] == 0 {
                mask[(x / 8) as usize] |= 0x80 >> (x % 8);
            }
        }
        out.extend(mask);
    }
    out
}

fn write_ico(source: &RgbaImage, path: &Path) -> io::Result<()> {
    let sizes = [16u32, 24, 32, 48, 64, 128, 256];
    let frames: Vec<_> = sizes.iter().map(|&n| icon_frame(&fit_icon(source, n))).collect();
    let mut header = Vec::new();
    u16le(&mut header, 0);
    u16le(&mut header, 1);
    u16le(&mut header, sizes.len() as u16);
    let mut offset = 6 + 16 * sizes.len() as u32;
    for (&size, frame) in sizes.iter().zip(&frames) {
        header.extend([size as u8, size as u8, 0, 0]); // 256 is encoded as 0
        u16le(&mut header, 1);
        u16le(&mut header, 32);
        u32le(&mut header, frame.len() as u32);
        u32le(&mut header, offset);
        offset += frame.len() as u32;
    }
    let mut file = fs::File::create(path)?;
    file.write_all(&header)?;
    for frame in frames { file.write_all(&frame)?; }
    Ok(())
}

fn required_path(key: &str) -> Result<PathBuf, Box<dyn Error>> {
    println!("cargo:rerun-if-env-changed={key}");
    let path = env::var_os(key).ok_or_else(|| format!("{key} is missing. Run build.cmd or build.ps1 instead of plain cargo build."))?;
    let path = PathBuf::from(path);
    println!("cargo:rerun-if-changed={}", path.display());
    Ok(path)
}

fn main() -> Result<(), Box<dyn Error>> {
    println!("cargo:rerun-if-changed=src/config.rs");
    let target_os = env::var("CARGO_CFG_TARGET_OS")?;
    if target_os != "windows" && target_os != "linux" {
        return Err("Use the Android Gradle project for Android; desktop supports Windows and Linux".into());
    }
    let config_path = required_path("WEBVIEW_APP_CONFIG")?;
    let icon_path = required_path("WEBVIEW_APP_ICON")?;
    let config_bytes = fs::read(config_path)?;
    let config: AppConfig = serde_json::from_slice(&config_bytes)?;
    config.validate()?;
    let out = PathBuf::from(env::var_os("OUT_DIR").ok_or("Missing OUT_DIR")?);
    fs::write(out.join("app-config.json"), config_bytes)?;

    let image = image::io::Reader::with_format(io::BufReader::new(fs::File::open(icon_path)?), image::ImageFormat::Png).decode()?.to_rgba8();
    fs::write(out.join("window-icon.rgba"), fit_icon(&image, 64).into_raw())?;
    fit_icon(&image, 256).save_with_format(out.join("splash-icon.png"), image::ImageFormat::Png)?;

    if target_os != "windows" { return Ok(()); }
    let ico_path = out.join("app.ico");
    write_ico(&image, &ico_path)?;
    let [a,b,c,d] = config.version_parts()?;
    let version = ((a as u64) << 48) | ((b as u64) << 32) | ((c as u64) << 16) | d as u64;
    let mut resource = winres::WindowsResource::new();
    resource.set_icon(ico_path.to_str().ok_or("Icon path is not UTF-8")?);
    resource.set("ProductName", &config.name);
    resource.set("CompanyName", "Jelofen1962");
    resource.set("Comments", "Powered by RWV (Rustmade-WebView) / Jelofen1962");
    resource.set("FileDescription", &config.name);
    resource.set("InternalName", &config.name);
    resource.set("ProductVersion", &config.version);
    resource.set("FileVersion", &config.version);
    resource.set_version_info(winres::VersionInfo::FILEVERSION, version);
    resource.set_version_info(winres::VersionInfo::PRODUCTVERSION, version);
    resource.compile()?;
    Ok(())
}
