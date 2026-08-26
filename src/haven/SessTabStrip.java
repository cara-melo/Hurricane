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
    private static final Color bg = new Color(0, 0, 0, 160);
    private static final Color fbg = new Color(32, 64, 32, 200);
    private static final Color frame = new Color(120, 160, 120, 255);
    private static final Color alertc = new Color(255, 205, 0, 255);
    private static final Color errorc = new Color(255, 80, 80, 255);
    private static final Color closec = new Color(220, 120, 120, 255);
    /* Fica por cima de tudo: widgets novos entram com z 0 e são inseridos antes
     * deste, tanto para desenho quanto para clique. */
    public static final int zorder = 10000;
    public static boolean show = Utils.getprefb("sesstabs", true);

    private final SessionSet set;
    private List<Item> items = new ArrayList<>();
    private Area addbtn = Area.sized(Coord.z, Coord.z);
    private String layoutid = null;

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
	List<Item> items = new ArrayList<>();
	int x = 0, h = 0;
	for(SessionTab tab : set.tabs()) {
	    Color col = (tab.error() != null) ? errorc : (tab.alert() ? alertc : Color.WHITE);
	    Item item = new Item(tab, font.render(tab.display(), col));
	    Coord isz = item.label.sz().add(pad.mul(2)).add(UI.scale(14), 0);
	    item.area = Area.sized(new Coord(x, 0), isz);
	    item.close = Area.sized(new Coord(x + isz.x - UI.scale(12), pad.y), UI.scale(new Coord(10, 10)));
	    items.add(item);
	    x += isz.x + gap;
	    h = Math.max(h, isz.y);
	}
	Coord asz = UI.scale(new Coord(14, 14));
	addbtn = Area.sized(new Coord(x, 0), asz);
	x += asz.x;
	h = Math.max(h, asz.y);
	this.items = items;
	this.layoutid = id();
	resize(new Coord(x, h));
	recenter();
    }

    private void recenter() {
	if(parent != null)
	    this.c = new Coord(Math.max(0, (parent.sz.x - sz.x) / 2), 0);
    }

    /* layout() corre no construtor, quando parent ainda é nulo e recenter() não
     * tem por onde se guiar; added() é o primeiro momento em que a barra sabe a
     * largura da raiz. Sem isto ela nasce encostada à esquerda e só se centra
     * quando um rótulo muda ou a janela é redimensionada. */
    protected void added() {
	super.added();
	recenter();
    }

    public void presize() {
	recenter();
    }

    public void tick(double dt) {
	super.tick(dt);
	if(!Utils.eq(layoutid, id()))
	    layout();
    }

    private void cross(GOut g, Area a) {
	g.line(a.ul, a.br.sub(1, 1), 1);
	g.line(new Coord(a.br.x - 1, a.ul.y), new Coord(a.ul.x, a.br.y - 1), 1);
    }

    public void draw(GOut g) {
	if(!show)
	    return;
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

    public boolean mousedown(MouseDownEvent ev) {
	if(!show || (ev.b != 1))
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

    public Object tooltip(Coord c, Widget prev) {
	if(!show)
	    return(null);
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
