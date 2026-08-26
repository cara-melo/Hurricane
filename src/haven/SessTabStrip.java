package haven;

import java.awt.Color;
import java.util.*;

/* Barra de abas de sessão: desenha e testa os cliques à mão em vez de criar um
 * widget por aba. São poucos retângulos, e o visual ainda vai ser acertado com
 * capturas do cliente rodando. */
public class SessTabStrip extends Widget {
    public static final Text.Foundry font = new Text.Foundry(Text.dfont, 11).aa(true);
    public static final Coord pad = UI.scale(new Coord(6, 2));
    public static final int gap = UI.scale(3);
    /* Espessura da pega de arrastar: o resto da barra é todo botão, portanto
     * arrastar precisa de um sítio próprio onde clicar. */
    public static final int gripsz = UI.scale(7);
    private static final Coord closesz = UI.scale(new Coord(10, 10));
    private static final Coord addsz = UI.scale(new Coord(14, 14));
    private static final Color bg = new Color(0, 0, 0, 160);
    private static final Color fbg = new Color(32, 64, 32, 200);
    private static final Color frame = new Color(120, 160, 120, 255);
    private static final Color alertc = new Color(255, 205, 0, 255);
    private static final Color errorc = new Color(255, 80, 80, 255);
    private static final Color closec = new Color(220, 120, 120, 255);
    private static final Color gripc = new Color(150, 150, 150, 255);
    /* Fica por cima de tudo: widgets novos entram com z 0 e são inseridos antes
     * deste, tanto para desenho quanto para clique. */
    public static final int zorder = 10000;
    public static boolean show = Utils.getprefb("sesstabs", true);
    public static boolean vertical = Utils.getprefb("sesstabsvert", false);
    /* Estáticos porque cada sessão tem a sua barra na raiz da própria UI:
     * arrastar uma tem de mover todas, e a pref é uma só. Nulo quer dizer "sem
     * posição escolhida", que é o centro do topo, como era antes de a barra ser
     * móvel. */
    private static Coord pos = Utils.getprefc("sesstabspos", null);

    private final SessionSet set;
    private List<Item> items = new ArrayList<>();
    private Area addbtn = Area.sized(Coord.z, Coord.z);
    private Area grip = Area.sized(Coord.z, Coord.z);
    private String layoutid = null;
    private boolean layoutvert = false;
    private UI.Grab dragging = null;
    private Coord doff = Coord.z;

    private static class Item {
	final SessionTab tab;
	final Text label;
	Area area, close;

	Item(SessionTab tab, Text label) {
	    this.tab = tab;
	    this.label = label;
	}
    }

    public SessTabStrip(SessionSet set) {
	super(Coord.z);
	this.set = set;
	z(zorder);
	layout();
    }

    /* Assinatura do que está desenhado: enquanto não muda, não vale re-render
     * do texto. */
    private String id() {
	StringBuilder buf = new StringBuilder();
	for(SessionTab tab : set.tabs()) {
	    buf.append(tab.display());
	    buf.append((tab.error() != null) ? '!' : (tab.alert() ? '*' : ':'));
	}
	return(buf.toString());
    }

    private void layout() {
	boolean vert = vertical;
	List<Item> items = new ArrayList<>();
	int lw = 0, lh = 0;
	for(SessionTab tab : set.tabs()) {
	    Color col = (tab.error() != null) ? errorc : (tab.alert() ? alertc : Color.WHITE);
	    Item item = new Item(tab, font.render(tab.display(), col));
	    items.add(item);
	    lw = Math.max(lw, item.label.sz().x);
	    lh = Math.max(lh, item.label.sz().y);
	}
	/* Altura de uma aba: igual nos dois modos, para trocar de orientação não
	 * mudar o tamanho do texto nem a posição do x de fechar. */
	int ih = Math.max(lh, closesz.y) + (pad.y * 2);
	Coord sz;
	if(vert) {
	    /* Todas as abas com a mesma largura: empilhadas, larguras diferentes
	     * dariam uma coluna serrilhada. */
	    int w = Math.max(lw + (pad.x * 2) + UI.scale(14), addsz.x);
	    grip = Area.sized(Coord.z, new Coord(w, gripsz));
	    int y = gripsz + gap;
	    for(Item item : items) {
		item.area = Area.sized(new Coord(0, y), new Coord(w, ih));
		item.close = Area.sized(new Coord(w - UI.scale(12), y + ((ih - closesz.y) / 2)), closesz);
		y += ih + gap;
	    }
	    addbtn = Area.sized(new Coord((w - addsz.x) / 2, y), addsz);
	    sz = new Coord(w, y + addsz.y);
	} else {
	    int h = Math.max(ih, addsz.y);
	    grip = Area.sized(Coord.z, new Coord(gripsz, h));
	    int x = gripsz + gap;
	    for(Item item : items) {
		int w = item.label.sz().x + (pad.x * 2) + UI.scale(14);
		item.area = Area.sized(new Coord(x, 0), new Coord(w, ih));
		item.close = Area.sized(new Coord(x + w - UI.scale(12), (ih - closesz.y) / 2), closesz);
		x += w + gap;
	    }
	    addbtn = Area.sized(new Coord(x, 0), addsz);
	    sz = new Coord(x + addsz.x, h);
	}
	/* Os Text que saem levam uma textura de GPU cada um e não há finalizador
	 * nenhum a apanhá-los. Não é detalhe: cada aba tem a sua barra, todas
	 * refazem o layout quando o rótulo ou o alerta de qualquer aba muda, e a
	 * thread de fundo ticka-as a 30 Hz -- sem isto uma privada recebida com N
	 * abas abertas deixa para trás da ordem de N² texturas. */
	for(Item old : this.items)
	    old.label.dispose();
	this.items = items;
	this.layoutid = id();
	this.layoutvert = vert;
	resize(sz);
	place();
    }

    /* Presa dentro da janela: uma resolução menor que a de quando a posição foi
     * guardada não pode deixar a barra fora do ecrã, e a barra vertical cresce
     * para baixo a cada aba nova. */
    private Coord clip(Coord c) {
	return(new Coord(Utils.clip(c.x, 0, Math.max(0, parent.sz.x - sz.x)),
			 Utils.clip(c.y, 0, Math.max(0, parent.sz.y - sz.y))));
    }

    private void place() {
	if(parent == null)
	    return;
	Coord p = pos;
	if(p == null)
	    /* Sem posição escolhida: a barra horizontal fica onde sempre esteve,
	     * centrada no topo; a coluna encosta à esquerda, que é onde uma
	     * coluna alta estorva menos. */
	    p = layoutvert ? Coord.z : new Coord((parent.sz.x - sz.x) / 2, 0);
	this.c = clip(p);
    }

    /* layout() corre no construtor, quando parent ainda é nulo e place() não tem
     * por onde se guiar; added() é o primeiro momento em que a barra sabe a
     * largura da raiz. Sem isto ela nasce encostada à esquerda e só se acerta
     * quando um rótulo muda ou a janela é redimensionada. */
    protected void added() {
	super.added();
	place();
    }

    public void dispose() {
	super.dispose();
	if(dragging != null) {
	    dragging.remove();
	    dragging = null;
	}
	for(Item item : items)
	    item.label.dispose();
	items = new ArrayList<>();
    }

    public void presize() {
	place();
    }

    public void tick(double dt) {
	super.tick(dt);
	if((vertical != layoutvert) || !Utils.eq(layoutid, id()))
	    layout();
	else if(dragging == null)
	    /* Todo o tick, e não só quando algo muda: a posição é estática e
	     * partilhada, portanto é assim que a barra das outras sessões
	     * acompanha um arrasto feito na sessão em foco. */
	    place();
    }

    private void cross(GOut g, Area a) {
	g.line(a.ul, a.br.sub(1, 1), 1);
	g.line(new Coord(a.br.x - 1, a.ul.y), new Coord(a.ul.x, a.br.y - 1), 1);
    }

    private void drawgrip(GOut g) {
	g.chcolor(bg);
	g.frect(grip.ul, grip.sz());
	g.chcolor(frame);
	g.rect(grip.ul, grip.sz());
	g.chcolor(gripc);
	Coord mid = grip.ul.add(grip.sz().div(2));
	Coord dot = UI.scale(new Coord(2, 2));
	for(int i = -1; i <= 1; i++) {
	    Coord d = layoutvert ? new Coord(mid.x + (i * UI.scale(4)), mid.y) : new Coord(mid.x, mid.y + (i * UI.scale(4)));
	    g.frect(d.sub(dot.div(2)), dot);
	}
	g.chcolor();
    }

    public void draw(GOut g) {
	if(!show)
	    return;
	drawgrip(g);
	for(Item item : items) {
	    g.chcolor(set.isfocused(item.tab) ? fbg : bg);
	    g.frect(item.area.ul, item.area.sz());
	    g.chcolor(frame);
	    g.rect(item.area.ul, item.area.sz());
	    g.chcolor();
	    g.image(item.label.tex(), item.area.ul.add(pad));
	    g.chcolor(closec);
	    cross(g, item.close);
	    g.chcolor();
	}
	g.chcolor(bg);
	g.frect(addbtn.ul, addbtn.sz());
	g.chcolor(frame);
	g.rect(addbtn.ul, addbtn.sz());
	Coord mid = addbtn.ul.add(addbtn.sz().div(2));
	g.line(new Coord(mid.x, addbtn.ul.y + UI.scale(3)), new Coord(mid.x, addbtn.br.y - UI.scale(3)), 1);
	g.line(new Coord(addbtn.ul.x + UI.scale(3), mid.y), new Coord(addbtn.br.x - UI.scale(3), mid.y), 1);
	g.chcolor();
    }

    private void startdrag(Coord c) {
	if(dragging != null)
	    dragging.remove();
	dragging = ui.grabmouse(this);
	doff = c;
    }

    public boolean mousedown(MouseDownEvent ev) {
	if(!show)
	    return(false);
	/* Botão do meio em qualquer ponto da barra também arrasta, como nos
	 * outros widgets móveis do cliente: com as abas todas cheias de botões,
	 * a pega sozinha é um alvo pequeno. */
	if((ev.b == 2) || ((ev.b == 1) && grip.contains(ev.c))) {
	    startdrag(ev.c);
	    return(true);
	}
	if(ev.b != 1)
	    return(false);
	for(Item item : items) {
	    if(item.close.contains(ev.c)) {
		SessCloseWnd.open(ui, item.tab);
		return(true);
	    }
	    if(item.area.contains(ev.c)) {
		set.focus(item.tab);
		return(true);
	    }
	}
	if(addbtn.contains(ev.c)) {
	    set.newtab();
	    return(true);
	}
	return(false);
    }

    public void mousemove(MouseMoveEvent ev) {
	if(dragging != null) {
	    pos = clip(this.c.add(ev.c).sub(doff));
	    this.c = pos;
	    return;
	}
	super.mousemove(ev);
    }

    public boolean mouseup(MouseUpEvent ev) {
	if(dragging != null) {
	    dragging.remove();
	    dragging = null;
	    Utils.setprefc("sesstabspos", pos);
	    return(true);
	}
	return(super.mouseup(ev));
    }

    public Object tooltip(Coord c, Widget prev) {
	if(!show)
	    return(null);
	if(grip.contains(c))
	    return("Drag to move the session tabs");
	for(Item item : items) {
	    if(item.close.contains(c))
		return("Log out " + item.tab.display());
	    if(item.area.contains(c)) {
		String err = item.tab.error();
		return((err == null) ? item.tab.display() : ("Session failed: " + err));
	    }
	}
	if(addbtn.contains(c))
	    return("New session");
	return(null);
    }
}
