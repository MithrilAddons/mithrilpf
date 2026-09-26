package dev.mithril.mithrilpf.update;

/** Harmless child process used only by installer tests. */
public final class WaitingProcess {
    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        System.out.flush();
        System.in.read();
    }
}
