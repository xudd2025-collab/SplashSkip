package com.codex.splashskip;

public final class UpdatePolicyCheck {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void rejectRepository(String input) {
        try { UpdatePolicy.repository(input); throw new AssertionError("Accepted unsafe repository: " + input); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        require(UpdatePolicy.repository("https://github.com/owner/SplashSkip.git/").equals("owner/SplashSkip"), "URL normalization");
        require(UpdatePolicy.repository("owner/SplashSkip").equals("owner/SplashSkip"), "Short repository");
        require(UpdatePolicy.repository("").isEmpty(), "Disabled update source");
        for (String input : new String[]{"http://github.com/owner/repo", "https://github.com.evil.example/owner/repo",
                "https://user:secret@github.com/owner/repo", "https://github.com:443/owner/repo", "https://github.com/owner/repo?token=secret",
                "https://github.com/owner/repo#fragment", "owner/../repo", "owner/repo/releases", "../../repo"}) rejectRepository(input);
        require(UpdatePolicy.newer("v0.10.0", "0.9.0"), "Numeric version ordering");
        require(UpdatePolicy.newer("v1.0.0", "0.99.0"), "Major version ordering");
        require(!UpdatePolicy.newer("v0.4.0", "0.4.0") && !UpdatePolicy.newer("v0.3.9", "0.4.0"), "Equal/older release");
        for (String input : new String[]{"v0.5.0-beta", "latest", "v999999999999999.0.0"}) {
            try { UpdatePolicy.version(input); throw new AssertionError("Accepted nonstable version " + input); }
            catch (IllegalArgumentException expected) { }
        }
        require(!UpdatePolicy.releaseUrl("owner/repo", "https://github.com/owner/repo/releases/download/v0.5.0/SplashSkip.apk", true).isEmpty(), "Official asset");
        require(UpdatePolicy.releaseUrl("owner/repo", "https://evil.example/SplashSkip.apk", true).isEmpty(), "External asset rejected");
        require(UpdatePolicy.releaseUrl("owner/repo", "https://github.com/another/repo/releases/tag/v0.5.0", false).isEmpty(), "Wrong repo rejected");
        System.out.println("PASS update repository, stable version ordering and release URL policy");
    }
}
