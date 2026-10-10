package com.demo.upimesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

class DashboardTest extends HttpTestSupport {

    @Test
    void rootServesTheDashboardPageAndItsAssets() throws Exception {
        // MockMvc does not follow forwards: "/" must forward to the static dashboard page.
        mvc.perform(get("/")).andExpect(MockMvcResultMatchers.status().isOk()).andExpect(forwardedUrl("index.html"));

        var page = mvc.perform(get("/index.html")).andReturn().getResponse();
        assertEquals(200, page.getStatus());
        assertTrue(page.getContentType().startsWith("text/html"));
        String html = page.getContentAsString();
        assertTrue(html.contains("UPI") && html.contains("Offline Mesh"));
        assertTrue(html.contains("app.js") && html.contains("app.css"));
        for (String asset : new String[]{"/app.js", "/app.css"}) {
            assertEquals(200, mvc.perform(get(asset)).andReturn().getResponse().getStatus(), asset);
        }
    }

    /** Guards against dead controls: every element the script looks up by id must exist in the page. */
    @Test
    void everyElementIdUsedByTheScriptExistsInThePage() throws Exception {
        String html = new String(getClass().getResourceAsStream("/static/index.html").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String js = new String(getClass().getResourceAsStream("/static/app.js").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> inPage = new java.util.HashSet<>();
        var idMatcher = java.util.regex.Pattern.compile("id=\"([A-Za-z0-9_-]+)\"").matcher(html);
        while (idMatcher.find()) inPage.add(idMatcher.group(1));

        java.util.Set<String> used = new java.util.TreeSet<>();
        var useMatcher = java.util.regex.Pattern.compile("\\$\\('#([A-Za-z0-9_-]+)'").matcher(js);
        while (useMatcher.find()) used.add(useMatcher.group(1));
        var idsMatcher = java.util.regex.Pattern.compile("setVal\\('([A-Za-z0-9]+)'").matcher(js);
        while (idsMatcher.find()) used.add(idsMatcher.group(1));

        // Elements the script itself creates at runtime.
        used.removeIf(id -> id.equals("atkTimes") || id.startsWith("t-"));
        used.removeAll(inPage);
        assertTrue(used.isEmpty(), "script references ids that are not in index.html: " + used);
        assertTrue(inPage.containsAll(java.util.List.of("btnSend", "btnGossip", "btnFlush", "btnReset", "topo", "ledger", "accounts", "stats",
                "mapScroll", "deviceDock", "expChart", "expSummary", "expDiag", "scenarioList", "btnRunAll", "adminBox")));
    }

    /** The Content-Security-Policy allows no inline script or style, so the page and its script must not need any. */
    @Test
    void pageAndScriptNeedNoInlineScriptStyleOrEventHandlers() throws Exception {
        String html = new String(getClass().getResourceAsStream("/static/index.html").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String js = new String(getClass().getResourceAsStream("/static/app.js").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(java.util.regex.Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(html).find(), "inline <script> block");
        assertFalse(java.util.regex.Pattern.compile("<style").matcher(html).find(), "inline <style> block");
        for (String source : new String[]{html, js}) {
            assertFalse(java.util.regex.Pattern.compile("\\sstyle=[\"']").matcher(source).find(), "inline style attribute");
            assertFalse(java.util.regex.Pattern.compile("\\son[a-z]+=[\"']").matcher(source).find(), "inline event handler attribute");
            assertFalse(source.contains("javascript:"), "javascript: URL");
        }
        assertFalse(js.contains("eval(") || js.contains("new Function("), "dynamic code evaluation");
    }

    /** Backend text is never put into the page unescaped: markup goes through the html tag, which escapes by default. */
    @Test
    void scriptInsertsBackendTextOnlyThroughEscapingHelpers() throws Exception {
        String js = new String(getClass().getResourceAsStream("/static/app.js").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(js.contains("const frag = v => (v instanceof Raw ? v.s : Array.isArray(v) ? v.map(frag).join('') : v === false || v === null || v === undefined ? '' : esc(v));"),
                "the html tag must escape every interpolated value that is not itself html");
        assertFalse(js.contains("document.write"), "document.write");
        assertFalse(js.contains("insertAdjacentHTML") || js.contains("outerHTML"), "other HTML sinks");
    }
}
