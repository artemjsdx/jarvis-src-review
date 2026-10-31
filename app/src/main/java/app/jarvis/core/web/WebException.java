package app.jarvis.core.web;

/**
 * Failure of a web service. {@link #getMessage()} is the technical string; {@link #userMessage} is Russian and safe to show.
 * {@link #cooldown} marks failures after which a search backend is skipped for a while (auth, quota, server, timeout).
 */
public final class WebException extends Exception {
    private static final long serialVersionUID = 1L;

    public final String userMessage;
    public final int status;
    public final boolean cooldown;

    public WebException(String userMessage, String tech, int status, boolean cooldown) {
        super(tech);
        this.userMessage = userMessage;
        this.status = status;
        this.cooldown = cooldown;
    }

    public static WebException http(String service, int status, String body) {
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (snippet.length() > 160) snippet = snippet.substring(0, 160) + "…";
        String tech = service + " HTTP " + status + (snippet.isEmpty() ? "" : ": " + snippet);
        String user;
        boolean cool;
        if (status == 401 || status == 403) { user = "«" + service + "» отклонил ключ или доступ (" + status + ")"; cool = true; }
        else if (status == 402) { user = "«" + service + "»: закончился баланс или лимит тарифа (402)"; cool = true; }
        else if (status == 429) { user = "«" + service + "»: превышен лимит запросов (429)"; cool = true; }
        else if (status >= 500) { user = "«" + service + "» временно недоступен (" + status + ")"; cool = true; }
        else { user = "«" + service + "» вернул ошибку " + status; cool = false; }
        return new WebException(user, tech, status, cool);
    }

    public static WebException timeout(String service) {
        return new WebException("«" + service + "» не ответил вовремя", service + " timeout", 0, true);
    }

    public static WebException unavailable(String service, String reason) {
        return new WebException("«" + service + "» недоступен: " + reason, service + " unavailable: " + reason, 0, true);
    }

    public static WebException parse(String service, String detail) {
        return new WebException("«" + service + "»: непонятный ответ сервиса", service + " parse: " + detail, 0, false);
    }
}
