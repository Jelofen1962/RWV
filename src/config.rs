use serde::Deserialize;

#[derive(Deserialize, Debug)]
pub struct AppConfig {
    pub name: String,
    pub version: String,
    pub website_url: String,
    #[serde(default)]
    pub trust_http_origin: bool,
}

impl AppConfig {
    pub fn validate(&self) -> Result<(), String> {
        if self.name.trim().is_empty() || self.name.chars().any(char::is_control) {
            return Err("App name must be nonempty and must not contain control characters".into());
        }
        self.version_parts()?;
        let url = url::Url::parse(&self.website_url).map_err(|e| format!("Invalid website URL: {e}"))?;
        match url.scheme() {
            "http" | "https" if url.host_str().is_some() => {},
            "file" if url.to_file_path().is_ok() => {},
            _ => return Err("Website URL must be http://, https:// or an absolute file:/// URL".into()),
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err("Do not put credentials in the website URL".into());
        }
        if self.trust_http_origin && url.scheme() != "http" {
            return Err("TrustHttpOrigin requires an http:// website URL".into());
        }
        Ok(())
    }

    pub fn version_parts(&self) -> Result<[u16; 4], String> {
        let mut result = [0u16; 4];
        let parts: Vec<_> = self.version.split('.').collect();
        if parts.is_empty() || parts.len() > 4 {
            return Err("Version must have 1 to 4 numeric components".into());
        }
        for (i, part) in parts.iter().enumerate() {
            if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
                return Err("Version components must be integers from 0 to 65535".into());
            }
            result[i] = part.parse().map_err(|_| "Version components must be integers from 0 to 65535".to_string())?;
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config(url: &str, version: &str) -> AppConfig {
        AppConfig { name: "Test app".into(), version: version.into(), website_url: url.into(), trust_http_origin: false }
    }
    #[test]
    fn accepts_http_and_https() {
        assert!(config("http://localhost:3355", "1.0").validate().is_ok());
        assert!(config("https://example.org", "1.2.3.4").validate().is_ok());
    }
    #[test]
    fn rejects_invalid_versions_and_credentials() {
        for version in ["", "1..2", "1.2.3.4.5", "65536", "-1"] {
            assert!(config("https://example.org", version).validate().is_err());
        }
        assert!(config("https://user:secret@example.org", "1").validate().is_err());
    }
    #[test]
    fn trust_requires_http() {
        let mut app = config("https://example.org", "1");
        app.trust_http_origin = true;
        assert!(app.validate().is_err());
        app.website_url = "http://example.org".into();
        assert!(app.validate().is_ok());
    }
}
