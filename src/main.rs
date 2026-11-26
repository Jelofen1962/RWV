#![cfg_attr(all(target_os = "windows", not(debug_assertions)), windows_subsystem = "windows")]

mod config;
mod browser;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use config::AppConfig;
use std::{error::Error, path::PathBuf, time::{Duration, Instant}};
use tao::{
    dpi::LogicalSize,
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoopBuilder},
    window::{Fullscreen, Icon, WindowBuilder},
};
use wry::{PageLoadEvent, WebViewBuilder};
#[cfg(target_os = "linux")]
use {tao::platform::unix::WindowExtUnix, wry::{WebContext, WebViewBuilderExtUnix}};

const CONFIG: &str = include_str!(concat!(env!("OUT_DIR"), "/app-config.json"));
const ICON_RGBA: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/window-icon.rgba"));
const SPLASH_ICON: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/splash-icon.png"));
const SPLASH: &str = include_str!("ui/splash.html");
const CONSOLE: &str = include_str!("ui/console.js");
const SPLASH_TIME: Duration = Duration::from_millis(1400);
const SPLASH_TIMEOUT: Duration = Duration::from_secs(5);

enum AppEvent { SplashLoaded }

fn escape_html(value: &str) -> String {
    value.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
        .replace('"', "&quot;").replace('\'', "&#39;")
}

fn splash_html(name: &str) -> String {
    SPLASH.replace("__APP_ICON__", &STANDARD.encode(SPLASH_ICON))
        .replace("__APP_NAME__", &escape_html(name))
}

fn profile_directory(config: &AppConfig, url: &url::Url) -> Result<PathBuf, Box<dyn Error>> {
    #[cfg(target_os = "windows")]
    let root = PathBuf::from(std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set")?);
    #[cfg(target_os = "linux")]
    let root = std::env::var_os("XDG_DATA_HOME").filter(|v| !v.is_empty() && PathBuf::from(v).is_absolute()).map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share")))
        .ok_or("Neither XDG_DATA_HOME nor HOME is set")?;
    let identity = if url.scheme() == "file" { url.as_str().to_owned() } else { url.origin().ascii_serialization() };
    let mut hash = 0xcbf29ce484222325u64;
    for byte in format!("{}\0{}", config.name, identity).bytes() {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    let engine = if cfg!(target_os = "windows") { "WebView2" } else { "WebKitGTK" };
    Ok(root.join("RustWebViewApps").join(format!("{hash:016x}")).join(engine))
}

fn run() -> Result<(), Box<dyn Error>> {
    let config: AppConfig = serde_json::from_str(CONFIG)?;
    config.validate()?;
    let url = url::Url::parse(&config.website_url)?;
    let profile = profile_directory(&config, &url)?;
    std::fs::create_dir_all(&profile)?;
    #[cfg(target_os = "windows")]
    std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &profile);
    #[cfg(target_os = "windows")]
    if config.trust_http_origin {
        let origin = url.origin().ascii_serialization();
        std::env::set_var("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", format!("--unsafely-treat-insecure-origin-as-secure={origin}"));
    }

    let event_loop = EventLoopBuilder::<AppEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let icon = Icon::from_rgba(ICON_RGBA.to_vec(), 64, 64)?;
    let window = WindowBuilder::new()
        .with_title(&config.name)
        .with_inner_size(LogicalSize::new(1200.0, 800.0))
        .with_min_inner_size(LogicalSize::new(320.0, 240.0))
        .with_fullscreen(if config.fullscreen { Some(Fullscreen::Borderless(None)) } else { None })
        .with_window_icon(Some(icon))
        .build(&event_loop)?;
    let splash = splash_html(&config.name);
    #[cfg(target_os = "linux")]
    let mut web_context = WebContext::new(Some(profile.clone()));
    #[cfg(target_os = "linux")]
    let builder = WebViewBuilder::with_web_context(&mut web_context);
    #[cfg(target_os = "windows")]
    let builder = WebViewBuilder::new().with_hotkeys_zoom(true);
    let builder = builder
        .with_html(&splash)
        .with_initialization_script(CONSOLE)
        .with_devtools(true)
        .with_on_page_load_handler(move |event, _url| {
            if matches!(event, PageLoadEvent::Finished) {
                let _ = proxy.send_event(AppEvent::SplashLoaded);
            }
        });
    #[cfg(target_os = "windows")]
    let webview = builder.build(&window)?;
    #[cfg(target_os = "linux")]
    let webview = builder.build_gtk(window.default_vbox().ok_or("No GTK container")?)?;

    #[cfg(target_os = "windows")]
    if let Err(error) = browser::enable_password_manager(&webview) {
        eprintln!("Native autofill unavailable: {error}");
    }
    #[cfg(target_os = "linux")]
    browser::install_password_manager(&webview, window.default_vbox().ok_or("No GTK container")?, &profile.to_string_lossy());

    let fallback = Instant::now() + SPLASH_TIMEOUT;
    let mut deadline = fallback;
    let mut splash_loaded = false;
    let mut opened = false;
    event_loop.run(move |event, _, control_flow| {
        *control_flow = if opened { ControlFlow::Wait } else { ControlFlow::WaitUntil(deadline) };
        let _keep_window = &window;
        #[cfg(target_os = "linux")]
        let _keep_context = &web_context;
        match event {
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                *control_flow = ControlFlow::Exit;
                return;
            }
            Event::UserEvent(AppEvent::SplashLoaded) if !opened && !splash_loaded => {
                splash_loaded = true;
                deadline = (Instant::now() + SPLASH_TIME).min(fallback);
                *control_flow = ControlFlow::WaitUntil(deadline);
            }
            _ => {}
        }
        if !opened && Instant::now() >= deadline {
            opened = true;
            if let Err(error) = webview.load_url(url.as_str()) {
                show_error(&format!("Couldn't open the website.\n\n{error}"));
                *control_flow = ControlFlow::Exit;
            } else {
                *control_flow = ControlFlow::Wait;
            }
        }
    });
}

#[cfg(target_os = "windows")]
#[link(name = "user32")]
extern "system" {
    fn MessageBoxW(hwnd: isize, text: *const u16, caption: *const u16, kind: u32) -> i32;
}

#[cfg(target_os = "windows")]
fn show_error(message: &str) {
    let text: Vec<u16> = message.encode_utf16().chain(Some(0)).collect();
    let title: Vec<u16> = "RWV | Something went wrong".encode_utf16().chain(Some(0)).collect();
    unsafe { MessageBoxW(0, text.as_ptr(), title.as_ptr(), 0x10); }
}

#[cfg(target_os = "linux")]
fn show_error(message: &str) {
    eprintln!("{message}");
}

fn main() {
    if let Err(error) = run() {
        let runtime = if cfg!(target_os = "windows") { "Microsoft Edge WebView2 Evergreen Runtime" } else { "GTK 3 and WebKitGTK 4.1" };
        show_error(&format!("The app couldn't start.\n\n{error}\n\nCheck that {runtime} is installed and the profile folder is writable."));
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_are_html_not_code() {
        assert_eq!(escape_html("<a>&\"'"), "&lt;a&gt;&amp;&quot;&#39;");
        let html = splash_html("<script>bad()</script>");
        assert!(html.contains("&lt;script&gt;bad()&lt;/script&gt;"));
        assert!(!html.contains("<script>bad()</script>"));
        assert!(!html.contains("__APP_ICON__"));
    }
}
