package app.jarvis.dev.checks;

import app.jarvis.core.log.ErrorSite;
import app.jarvis.core.util.CancelToken;

/** Throws from app code (CancelToken.throwIfCancelled) and prints the site the journal would record. */
public final class JournalSiteCheck {
    private JournalSiteCheck() { }

    public static void main(String[] args) {
        try {
            CancelToken token = new CancelToken();
            token.cancel("проверка");
            token.throwIfCancelled();
            System.out.println("NO EXCEPTION");
        } catch (Throwable e) {
            try {
                ErrorSite s = ErrorSite.from(e);
                System.out.println("where=" + s.where());
                System.out.println("fingerprint=" + s.fingerprint);
                System.out.println("exception=" + s.exception);
                System.out.println("frames=" + s.frames.size());
                System.out.println("first=" + (s.frames.isEmpty() ? "" : s.frames.get(0)));
            } catch (Throwable inner) {
                inner.printStackTrace(System.out);
            }
        }
    }
}
