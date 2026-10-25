package com.assetboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** The update check without GitHub's API: the release page's redirect, the APK's name, the notes, and who refused. */
class ReleasePageTest {
    private val repo = "konghw-git/OpenAssetBoard"

    @Test fun theRedirectNamesTheTag() {
        assertEquals("v0.9.12", ReleasePage.tagOf("https://github.com/$repo/releases/tag/v0.9.12"))
        assertEquals("v0.9.12", ReleasePage.tagOf("https://github.com/$repo/releases/tag/v0.9.12?from=latest"))
        // No release yet: GitHub sends the releases list instead.
        assertNull(ReleasePage.tagOf("https://github.com/$repo/releases"))
        assertNull(ReleasePage.tagOf(null))
    }

    /** The name scripts/publish-release.sh uploads; the page fallback can only guess it from the version. */
    @Test fun theApkIsNamedAsThePublishScriptNamesIt() {
        assertEquals(
            "https://github.com/$repo/releases/download/v0.9.12/OpenAssetBoard-0.9.12.apk",
            ReleasePage.apkUrl(repo, "v0.9.12"),
        )
        val script = File("../scripts/publish-release.sh").takeIf { it.exists() } ?: File("scripts/publish-release.sh")
        val text = script.readText()
        assert("APK=\"build/release/OpenAssetBoard-\$VERSION.apk\"" in text) { "the APK's name changed in publish-release.sh" }
        assert("TAG=\"v\$VERSION\"" in text) { "the tag's form changed in publish-release.sh" }
    }

    @Test fun theNotesAreTheVersionsSectionWithoutItsHeading() {
        val changelog = """
            # Changelog

            ## [Unreleased]

            ## [0.9.12] - 2026-10-25

            ### Added

            - 记账

            ## [0.9.11] - 2026-10-24

            - older
        """.trimIndent()
        assertEquals("### Added\n\n- 记账", ReleasePage.notes(changelog, "0.9.12"))
        assertEquals("- older", ReleasePage.notes(changelog, "0.9.11"))
        assertEquals("", ReleasePage.notes(changelog, "0.9.13"))
    }

    @Test fun onlyGitHubsSpentQuotaReadsAsTooMany() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        // 2026-10-25 23:23:31 in Shanghai.
        assertEquals("GitHub 接口每个网络每小时只给 60 次，这个网络已经用完，23:23 后恢复", refusal(403, "api.github.com", 1792941811, shanghai))
        assertEquals("gh-proxy.com 拒绝了请求（403）", refusal(403, "gh-proxy.com", null, shanghai))
        assertEquals("api.github.com 拒绝了请求（429）", refusal(429, "api.github.com", null, shanghai))
        assertEquals("github.com 返回 404", refusal(404, "github.com", null, shanghai))
    }
}
