package app.jarvis.dev.checks;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.web.HtmlToText;
import app.jarvis.core.web.SearchBackends;
import app.jarvis.core.web.SearchHit;
import app.jarvis.core.web.SearchRequest;
import app.jarvis.core.web.TextDecoder;
import app.jarvis.core.web.WebFetcher;
import java.nio.charset.Charset;
import java.util.List;

/** Runs the web core on the phone's real ART runtime: HttpURLConnection, org.json and charset tables of Android. */
public final class WebArtCheck {
    private WebArtCheck() { }

    public static void main(String[] args) {
        try {
            String html = "<html><head><title>Тест &amp; ART</title></head><body><nav>меню</nav><article><h1>Заголовок</h1>"
                + "<p>Абзац с <a href='/x?utm_source=1'>ссылкой</a>.</p><table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table></article></body></html>";
            HtmlToText.Result r = HtmlToText.convert(html, "https://example.org/", true);
            System.out.println("ART html title=" + r.title + " links=" + r.links.size());
            System.out.println(r.text);
            byte[] cp = "<meta charset=windows-1251><p>Привет</p>".getBytes(Charset.forName("windows-1251"));
            System.out.println("ART cp1251=" + TextDecoder.decode(cp, "text/html"));
            List<SearchHit> hits = SearchBackends.Brave.parse("{\"web\":{\"results\":[{\"title\":\"T\",\"url\":\"https://www.a.org/p/?utm_medium=x\",\"description\":\"d\"}]}}", 5);
            System.out.println("ART brave hit=" + hits.get(0).url + " source=" + hits.get(0).source);
            SearchBackends.OpenRouter.Parsed p = SearchBackends.OpenRouter.parse(
                "{\"choices\":[{\"message\":{\"content\":\"x\",\"annotations\":[{\"type\":\"url_citation\",\"url_citation\":{\"url\":\"https://b.org/\",\"title\":\"B\"}}]}}],\"usage\":{\"prompt_tokens\":3,\"cost\":0.5}}", 5);
            System.out.println("ART openrouter hits=" + p.hits.size() + " cost=" + p.cost);
            long t0 = System.currentTimeMillis();
            WebFetcher.Page page = new WebFetcher(null).fetch("https://ru.wikipedia.org/wiki/Гомель", "markdown", 600, 0, new CancelToken());
            System.out.println("ART live wiki status=" + page.status + " title=" + page.title + " total=" + page.totalChars
                + " next=" + page.nextOffset + " ms=" + (System.currentTimeMillis() - t0));
            System.out.println(page.text.replace('\n', ' '));
            SearchBackends.OpenRouter dummy = null;
            System.out.println("ART done " + (dummy == null) + " " + new SearchRequest("q", 1, "", "").text());
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
    }
}
