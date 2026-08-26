package haven;

import java.util.*;

/* Lista de sessões abertas e qual está em foco.
 *
 * Ordem de locks do cliente inteiro: monitor da UI primeiro, monitor do
 * SessionSet a seguir, uilock do UILoop depois, nunca ao contrário. A UI vem
 * antes porque a thread de render roda dispatch(ui) dentro de synchronized(ui),
 * portanto todo clique na barra de abas e toda tecla de sessão entram aqui já
 * com o monitor da UI na mão. As chamadas ao Host acontecem com o monitor desta
 * classe na mão -- é o que garante que quem lê focused() e quem troca a UI
 * desenhada não se cruzam. Para a ordem valer, duas coisas:
 *
 *  - Nada que já segure o uilock pode perguntar nada a esta classe: é por isso
 *    que o UILoop guarda a aba em foco num campo próprio (focustab) em vez de
 *    chamar isfocused().
 *  - Nada chamado de dentro daqui pode pegar no monitor de uma UI. É por isso
 *    que UI.clearmods() não sincroniza. Sem essa regra, fechar a aba em foco e
 *    clicar noutra antes de a thread dela desenrolar trava o cliente: a thread
 *    de render espera por este monitor com o da UI na mão, e a thread da aba
 *    espera pelo da UI com este na mão. */
public class SessionSet {
    /* O lado que mexe em UI, render e globais. Implementado pelo Client; o
     * teste headless passa um stub. */
    public interface Host {
	void focus(SessionTab tab);   /* passou a ser a aba em foco */
	void spawn();                 /* criar e iniciar uma aba nova */
	void closed(SessionTab tab);  /* saiu da lista: destruir a UI dela */
	void empty();                 /* não sobrou aba nenhuma */
    }

    private final Host host;
    /* Lista imutável, trocada sob o monitor: quem só lê não pega em lock
     * nenhum. Não é micro-optimização, é o que fecha a questão dos locks. Quem
     * lê esta classe chega cá quase sempre já a segurar outra coisa -- a barra
     * de abas desenha com o monitor da UI na mão, o alerta sai de dentro de
     * ui.msg(), a thread de fundo pergunta quem está em foco a cada volta -- e
     * se qualquer dessas leituras pedisse o monitor daqui fechava-se o ciclo
     * UI -> SessionSet -> uilock -> UI. Só quem escreve sincroniza. */
    private volatile List<SessionTab> tabs = Collections.emptyList();
    private volatile SessionTab focused = null;

    public SessionSet(Host host) {
	this.host = host;
    }

    public List<SessionTab> tabs() {return(tabs);}
    public int size() {return(tabs.size());}
    public SessionTab focused() {return(focused);}
    public boolean isfocused(SessionTab tab) {return(tab == focused);}

    public synchronized void add(SessionTab tab) {
	List<SessionTab> tabs = new ArrayList<>(this.tabs);
	tabs.add(tab);
	this.tabs = Collections.unmodifiableList(tabs);
	if(focused == null)
	    focus(tab);
    }

    /* Sincronizado do princípio ao fim, callback incluído: se o monitor fosse
     * largado antes de host.focus, duas trocas de foco simultâneas (um clique na
     * barra e uma aba a fechar-se) podiam chegar ao Host pela ordem inversa e
     * deixar focused() a apontar para uma aba e a thread de render a desenhar
     * outra -- e nesse estado a thread de fundo salta a aba que julga estar em
     * foco, deixando-a sem tick nenhum. */
    public synchronized void focus(SessionTab tab) {
	if(!tabs.contains(tab) || (tab == focused))
	    return;
	focused = tab;
	tab.alert(false);
	host.focus(tab);
    }

    public synchronized void cycle(int dir) {
	List<SessionTab> tabs = this.tabs;
	if(tabs.isEmpty())
	    return;
	int i = tabs.indexOf(focused);
	if(i < 0)
	    i = 0;
	focus(tabs.get(Math.floorMod(i + dir, tabs.size())));
    }

    public synchronized void select(int idx) {
	List<SessionTab> tabs = this.tabs;
	if((idx < 0) || (idx >= tabs.size()))
	    return;
	focus(tabs.get(idx));
    }

    public synchronized void remove(SessionTab tab) {
	List<SessionTab> tabs = new ArrayList<>(this.tabs);
	int i = tabs.indexOf(tab);
	if(i < 0)
	    return;
	tabs.remove(i);
	this.tabs = Collections.unmodifiableList(tabs);
	boolean last = false;
	if(focused == tab) {
	    focused = null;
	    if(tabs.isEmpty())
		last = true;
	    else
		/* O foco muda antes de a UI antiga ser descartada, para a thread
		 * de render nunca ficar apontada para uma UI morta. */
		focus(tabs.get(Math.min(i, tabs.size() - 1)));
	}
	host.closed(tab);
	if(last)
	    host.empty();
    }

    public void newtab() {
	host.spawn();
    }

    public void alert(SessionTab tab) {
	if(!isfocused(tab))
	    tab.alert(true);
    }

    /* Configuração gráfica é do cliente, não da aba: quando uma UI muda, as
     * outras acompanham sem regravar as prefs nem propagar de volta. */
    public void spreadgprefs(UI src, GSettings prefs) {
	for(SessionTab tab : tabs()) {
	    UI ui = tab.ui;
	    if((ui != null) && (ui != src))
		ui.setgprefsq(prefs);
	}
    }
}
