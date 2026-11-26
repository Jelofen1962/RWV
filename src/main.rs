#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod config;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use config::AppConfig;
use std::{error::Error, path::PathBuf, time::{Duration, Instant}};
use tao::{
    dpi::LogicalSize,
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoopBuilder},
    window::{Icon, WindowBuilder},
};
use wry::{PageLoadEvent, WebViewBuilder};

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
    // Icon first, so an app name that happens to contain a marker stays untouched.
    SPLASH.replace("__APP_ICON__", &STANDARD.encode(SPLASH_ICON))
        .replace("__APP_NAME__", &escape_html(name))
}

fn profile_directory(config: &AppConfig, url: &url::Url) -> Result<PathBuf, Box<dyn Error>> {
    let root = std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set")?;
    let identity = if url.scheme() == "file" { url.as_str().to_owned() } else { url.origin().ascii_serialization() };
    // Same name + origin = same cookies, even after a rebuild.
    let mut hash = 0xcbf29ce484222325u64;
    for byte in format!("{}\0{}", config.name, identity).bytes() {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    Ok(PathBuf::from(root).join("RustWebViewApps").join(format!("{hash:016x}")).join("WebView2"))
}

fn run() -> Result<(), Box<dyn Error>> {
    let config: AppConfig = serde_json::from_str(CONFIG)?;
    config.validate()?;
    let url = url::Url::parse(&config.website_url)?;
    let profile = profile_directory(&config, &url)?;
    std::fs::create_dir_all(&profile)?;
    std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &profile);
    if config.trust_http_origin {
        // Let the dev server use secure-context APIs without nuking browser security.
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
        .with_window_icon(Some(icon))
        .build(&event_loop)?;
    let splash = splash_html(&config.name);
    let webview = WebViewBuilder::new()
        .with_html(&splash)
        .with_initialization_script(CONSOLE)
        .with_devtools(true)
        .with_hotkeys_zoom(true)
        .with_on_page_load_handler(move |event, _url| {
            if matches!(event, PageLoadEvent::Finished) {
                let _ = proxy.send_event(AppEvent::SplashLoaded);
            }
        })
        .build(&window)?;

    // No sleeping on the UI thread. Even a slow font CDN can't trap the splash.
    let fallback = Instant::now() + SPLASH_TIMEOUT;
    let mut deadline = fallback;
    let mut splash_loaded = false;
    let mut opened = false;
    event_loop.run(move |event, _, control_flow| {
        *control_flow = if opened { ControlFlow::Wait } else { ControlFlow::WaitUntil(deadline) };
        let _keep_window = &window;
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
            // Same WebView, same profile. Just swap the splash for the real site.
            if let Err(error) = webview.load_url(url.as_str()) {
                show_error(&format!("Couldn't open the website.\n\n{error}"));
                *control_flow = ControlFlow::Exit;
            } else {
                *control_flow = ControlFlow::Wait;
            }
        }
    });
}

#[link(name = "user32")]
extern "system" {
    fn MessageBoxW(hwnd: isize, text: *const u16, caption: *const u16, kind: u32) -> i32;
}

fn show_error(message: &str) {
    let text: Vec<u16> = message.encode_utf16().chain(Some(0)).collect();
    let title: Vec<u16> = "RWV | Something went wrong".encode_utf16().chain(Some(0)).collect();
    unsafe { MessageBoxW(0, text.as_ptr(), title.as_ptr(), 0x10); }
}

fn main() {
    if let Err(error) = run() {
        show_error(&format!("The app couldn't start.\n\n{error}\n\nCheck that Microsoft Edge WebView2 Evergreen Runtime is installed and the profile folder is writable."));
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
