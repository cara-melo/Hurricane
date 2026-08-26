package haven;

/* Uma sessão do cliente: a thread que roda a cadeia de runners, a UI atual e o
 * que a barra de abas precisa mostrar. O modelo não cria nem destrói UI --
 * quem faz isso é o Client, através de SessionSet.Host. */
public class SessionTab {
    public final SessionSet set;
    /* Escrita pelo UILoop sob uilock e lida sem lock nenhum pela thread de tick
     * de fundo, pela de render e pela da própria aba: tem de ser volatile, ou a
     * thread de fundo pode ver uma UI meio construída. */
    public volatile UI ui = null;     /* UI atual; nula até o primeiro newui da aba */
    public UI.Runner runner = null;   /* runner atual da cadeia */
    public Thread th = null;          /* thread da aba, atribuída pelo Client */
    private String label = "...";
    private boolean alert = false;
    private String error = null;

    public SessionTab(SessionSet set) {
	this.set = set;
    }

    public synchronized void label(String label) {this.label = label;}
    public synchronized String label() {return(label);}
    public synchronized void alert(boolean alert) {this.alert = alert;}
    public synchronized boolean alert() {return(alert);}
    public synchronized void error(String error) {this.error = error;}
    public synchronized String error() {return(error);}

    /* O que a aba mostra: o nome do personagem quando há um em jogo, senão o
     * rótulo do runner (a conta, na tela de login). */
    public String display() {
	UI ui = this.ui;
	if(ui != null) {
	    GameUI gui = ui.gui;
	    if((gui != null) && (gui.chrid != null) && !gui.chrid.equals(""))
		return(gui.chrid);
	}
	return(label());
    }

    /* Uma aba viva sai pela interrupção da própria thread, que desenrola a
     * cadeia de runners e chama set.remove() no fim. Uma aba morta por erro não
     * tem mais thread para interromper. */
    public void close() {
	if(error() != null) {
	    set.remove(this);
	} else {
	    Thread th = this.th;
	    if(th != null)
		th.interrupt();
	}
    }
}
