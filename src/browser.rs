
#[cfg(target_os = "windows")]
pub fn enable_password_manager(webview: &wry::WebView) -> Result<(), Box<dyn std::error::Error>> {
    use webview2_com::Microsoft::Web::WebView2::Win32::ICoreWebView2Settings4;
    use windows_core::Interface;
    use wry::WebViewExtWindows;
    unsafe {
        let core = webview.controller().CoreWebView2()?;
        let settings: ICoreWebView2Settings4 = core.Settings()?.cast()?;
        settings.SetIsPasswordAutosaveEnabled(true)?;
        settings.SetIsGeneralAutofillEnabled(true)?;
    }
    Ok(())
}

#[cfg(any(test, target_os = "linux"))]
fn credential_origin(raw: &str) -> Option<String> {
    let url = url::Url::parse(raw).ok()?;
    if url.scheme() != "https" || url.host_str().is_none() || !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    Some(url.origin().ascii_serialization())
}

#[cfg(target_os = "linux")]
mod linux {
    use super::credential_origin;
    use gtk::prelude::*;
    use serde::{Deserialize, Serialize};
    use std::{io::Write, process::{Command, Stdio}};
    use webkit2gtk::prelude::*;
    use wry::WebViewExtUnix;

    #[derive(Default, Serialize, Deserialize)]
    struct Login { username: String, password: String }

    fn command(profile: &str, origin: &str, action: &str) -> Command {
        let mut cmd = Command::new("secret-tool");
        cmd.arg(action);
        if action == "store" { cmd.arg("--label=RWV website login"); }
        cmd.args(["application", "rwv", "profile", profile, "origin", origin]);
        cmd.stderr(Stdio::null());
        cmd
    }
    fn lookup(profile: &str, origin: &str) -> Option<Login> {
        let output = command(profile, origin, "lookup").output().ok()?;
        if !output.status.success() { return None; }
        serde_json::from_slice(&output.stdout).ok()
    }
    fn store(profile: &str, origin: &str, login: &Login) -> bool {
        let Ok(data) = serde_json::to_vec(login) else { return false; };
        let Ok(mut child) = command(profile, origin, "store").stdin(Stdio::piped()).stdout(Stdio::null()).spawn() else { return false; };
        let written = child.stdin.take().map(|mut input| input.write_all(&data).is_ok()).unwrap_or(false);
        child.wait().map(|s| s.success() && written).unwrap_or(false)
    }
    fn clear(profile: &str, origin: &str) -> bool {
        command(profile, origin, "clear").stdout(Stdio::null()).status().map(|s| s.success()).unwrap_or(false)
    }
    fn copy_temporarily(value: &str) {
        let clipboard = gtk::Clipboard::get(&gtk::gdk::SELECTION_CLIPBOARD);
        clipboard.set_text(value);
        let copied = value.to_string();
        gtk::glib::timeout_add_seconds_local_once(45, move || {
            if clipboard.wait_for_text().as_deref() == Some(copied.as_str()) { clipboard.clear(); }
        });
    }
    fn message(text: &str) {
        let dialog = gtk::MessageDialog::new(None::<&gtk::Window>, gtk::DialogFlags::MODAL,
            gtk::MessageType::Info, gtk::ButtonsType::Close, text);
        dialog.connect_response(|d, _| d.close());
        dialog.show_all();
    }
    pub fn install(webview: &wry::WebView, container: &gtk::Box, profile: &str) {
        let button = gtk::Button::with_label("Passwords");
        button.set_tooltip_text(Some("Save or copy a login for the current HTTPS website using your system keyring"));
        container.pack_start(&button, false, false, 0);
        container.reorder_child(&button, 0);
        button.show();
        let native = webview.webview();
        let profile = profile.to_owned();
        button.connect_clicked(move |_| {
            let Some(origin) = native.uri().as_deref().and_then(credential_origin) else {
                message("Passwords are available only on HTTPS websites."); return;
            };
            if Command::new("secret-tool").arg("--version").stdout(Stdio::null()).stderr(Stdio::null()).status().is_err() {
                message("Install libsecret-tools and start/unlock your desktop Secret Service to use saved passwords."); return;
            }
            let login = lookup(&profile, &origin).unwrap_or_default();
            let dialog = gtk::Dialog::new();
            dialog.set_title("RWV saved login");
            dialog.set_modal(true);
            dialog.set_default_size(420, 180);
            let content = dialog.content_area();
            let label = gtk::Label::new(Some(&format!("Login for {origin}\nStored in your system keyring. Copy to paste into the website.")));
            label.set_line_wrap(true);
            content.add(&label);
            let user = gtk::Entry::new(); user.set_placeholder_text(Some("Username")); user.set_text(&login.username);
            let pass = gtk::Entry::new(); pass.set_placeholder_text(Some("Password")); pass.set_visibility(false); pass.set_text(&login.password);
            content.add(&user); content.add(&pass);
            dialog.add_button("Close", gtk::ResponseType::Close);
            dialog.add_button("Forget", gtk::ResponseType::Reject);
            dialog.add_button("Copy username", gtk::ResponseType::Other(1));
            dialog.add_button("Copy password", gtk::ResponseType::Other(2));
            dialog.add_button("Save", gtk::ResponseType::Accept);
            let profile = profile.clone();
            let native = native.clone();
            dialog.connect_response(move |d, response| {
                if response == gtk::ResponseType::Close || response == gtk::ResponseType::DeleteEvent { d.close(); return; }
                if native.uri().as_deref().and_then(credential_origin).as_deref() != Some(origin.as_str()) {
                    d.close(); message("The website changed. Reopen the password dialog."); return;
                }
                match response {
                    gtk::ResponseType::Accept => {
                        let login = Login { username: user.text().to_string(), password: pass.text().to_string() };
                        if login.password.is_empty() { message("Enter a password first."); }
                        else if store(&profile, &origin, &login) { d.close(); }
                        else { message("Could not save to the system keyring. Check that Secret Service is available and unlocked."); }
                    }
                    gtk::ResponseType::Reject => {
                        if clear(&profile, &origin) { d.close(); }
                        else { message("Could not remove the login from the keyring."); }
                    }
                    gtk::ResponseType::Other(1) => { copy_temporarily(user.text().as_str()); d.close(); }
                    gtk::ResponseType::Other(2) => { copy_temporarily(pass.text().as_str()); d.close(); }
                    _ => d.close(),
                }
            });
            dialog.show_all();
        });
    }
}
#[cfg(target_os = "linux")]
pub use linux::install as install_password_manager;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn vault_keys_are_exact_https_origins() {
        assert_eq!(credential_origin("https://example.org/a?x=1").as_deref(), Some("https://example.org"));
        assert_eq!(credential_origin("https://example.org:8443/a").as_deref(), Some("https://example.org:8443"));
        for url in ["http://example.org", "file:///tmp/a", "https://user:pw@example.org", "about:blank", "javascript:alert(1)"] {
            assert!(credential_origin(url).is_none());
        }
    }
}
