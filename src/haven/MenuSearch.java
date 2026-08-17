/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.awt.Color;
import haven.MenuGrid.Pagina;
import haven.MenuGrid.PagButton;

/* A janela de busca de ações, em três colunas: famílias, lista com a barra de
 * busca, informações da ação selecionada.
 *
 * Esta classe é o coordenador. Ela monta as colunas, distribui a largura,
 * trata colapso, resize e prefs, e concentra o filtro. As colunas não falam
 * entre si: a árvore avisa que a família mudou, a lista avisa que a seleção
 * mudou, e a janela empurra o PagButton para o painel. */
public abstract class MenuSearch extends Window {
    public static final Text.Foundry elf = CharWnd.attrf;
    public static final int elh = elf.height() + UI.scale(2);

    /* Larguras base, usadas como pesos: cada coluna leva sua fração do espaço
     * disponível, e as três crescem juntas. */
    public static final int treew = UI.scale(150), listw = UI.scale(250), infow = UI.scale(300);
    public static final int treemin = UI.scale(90), listmin = UI.scale(140), infomin = UI.scale(160);
    /* A faixa entre duas colunas. Quando a coluna ao lado está colapsada ela é
     * tudo o que sobra dela, que é a faixa clicável de 15 do design. */
    public static final int barw = UI.scale(15);
    public static final int minlisth = UI.scale(200);
    public static final int deflisth = UI.scale(500);
    /* Altura reservada no pé da coluna 3 para o punho de resize do deco
     * aparecer. Window.java:303 desenha o sizer em ca.br, e o deco é desenhado
     * antes das colunas porque link() o põe na cabeça da lista de filhos
     * (z == -100, Widget.java:433), então um frect2 opaco por cima o apagaria. */
    public static final int sizerh = UI.scale(16);

    public static final String pref_sz = "wndsz-srch";
    public static final String pref_tree = "srch-tree-collapsed";
    public static final String pref_info = "srch-info-collapsed";

    public static final KeyBinding kb_itemcraft = KeyBinding.get("scm-itemcraft", KeyMatch.nil);

    public final MenuGrid menu;
    public final MenuSearchTree tree;
    public final MenuSearchList rls;
    public final MenuSearchInfo info;
    public final CollapseBar treebar, infobar;
    public final Fallback fbmsg;
    public final TextEntry sbox;
    public final Button ingbtn;

    protected List<Result> cur = Collections.emptyList();
    protected List<Result> filtered = Collections.emptyList();
    protected Pagina scope = null;
    private boolean recons = true;
    private boolean reanc = false;
    private boolean treecol, infocol;
    /* A largura que a coluna tinha quando foi colapsada, devolvida ao expandir. */
    private int treerest = treew, inforest = infow;

    public class Result {
	public final PagButton btn;
	private Set<Pagina> anc = null;

	protected Result(PagButton btn) {
	    this.btn = btn;
	}

	/* Toda pagina desta até a raiz, inclusive ela mesma, calculada uma vez
	 * para que o teste de escopo seja um contains e não uma caminhada de
	 * parent() por tecla digitada. */
	public Set<Pagina> ancestors() {
	    if(anc == null)
		anc = MenuSearchLogic.ancestors(btn.pag, Pagina::parent);
	    return(anc);
	}

	/* O cache vale enquanto a árvore não muda de forma. Um pagseq novo pode
	 * reparentar uma categoria intermediária sem invalidar a pagina desta
	 * folha, e aí o PagButton sobrevive ao updlist com a cadeia velha: a
	 * árvore mostra a folha sob o pai novo e o filtro por essa família não a
	 * encontra, para sempre. */
	protected void reanc() {
	    anc = null;
	}

	public boolean inscope(Pagina scope) {
	    if(scope == null)
		return(true);
	    try {
		return(ancestors().contains(scope));
	    } catch(Loading l) {
		return(false);
	    } catch(RuntimeException e) {
		/* Recurso quebrado: fica fora do escopo, como o que ainda está
		 * carregando. Ver MenuSearchLogic.tree. */
		return(false);
	    }
	}
    }

    /* A linha fina acima da lista quando uma busca com escopo não achou nada e
     * o que está sendo mostrado é global. Ocupa altura dentro da coluna 2 em
     * vez de flutuar sobre as linhas. */
    public static class Fallback extends Widget {
	public static final Color col = new Color(255, 208, 128, 255);
	private Text.Line rend = null;
	private String cur = null;

	public Fallback(int w) {
	    super(Coord.of(w, elh));
	}

	public void settext(String text) {
	    if(Utils.eq(text, cur))
		return;
	    if(rend != null)
		rend.dispose();
	    rend = elf.render(text, col);
	    cur = text;
	}

	public void draw(GOut g) {
	    g.chcolor(0, 0, 0, 160);
	    g.frect2(Coord.z, sz);
	    g.chcolor();
	    if(rend != null)
		g.image(rend.tex(), Coord.of(UI.scale(3), (sz.y - rend.sz().y) / 2));
	}

	public void dispose() {
	    super.dispose();
	    if(rend != null) {
		rend.dispose();
		rend = null;
	    }
	}
    }

    /* A faixa de 15 entre duas colunas. Clicar colapsa ou expande a coluna do
     * lado indicado. */
    public class CollapseBar extends Widget {
	public final boolean left;

	public CollapseBar(boolean left) {
	    super(Coord.of(barw, elh));
	    this.left = left;
	}

	private boolean collapsed() {
	    return(left ? treecol : infocol);
	}

	public void draw(GOut g) {
	    g.chcolor(60, 50, 34, 255);
	    g.frect2(Coord.z, sz);
	    g.chcolor(200, 180, 140, 255);
	    /* A seta aponta para onde o clique vai mover a coluna. */
	    boolean pointleft = (left != collapsed());
	    int mx = sz.x / 2, my = sz.y / 2, a = UI.scale(4);
	    int tip = pointleft ? (mx - (a / 2)) : (mx + (a / 2));
	    int tail = pointleft ? (mx + (a / 2)) : (mx - (a / 2));
	    g.line(Coord.of(tail, my - a), Coord.of(tip, my), 1.0);
	    g.line(Coord.of(tip, my), Coord.of(tail, my + a), 1.0);
	    g.chcolor();
	}

	public boolean mousedown(MouseDownEvent ev) {
	    if(ev.b == 1) {
		/* O canto inferior direito pertence ao punho de resize do deco,
		 * que só recebe o evento depois de nós: PointerEvent.propagation
		 * percorre lchild -> prev (Widget.java:981) e o deco está na
		 * cabeça da lista. Devolvendo false o clique continua o caminho
		 * até ele.
		 *
		 * A zona é a de Window.java:352 convertida para coordenadas da
		 * área de conteúdo, e a margem entra na conta: lá o teste é
		 * contra ca.br, que é aa.br mais a margem nos dois eixos
		 * (Window.java:252-253). Sem somar a margem a faixa devolvida
		 * aqui é maior que a que o deco aceita, e a diagonal entre as
		 * duas cai em DragDeco.checkhit -- clique na barra, janela
		 * andando. */
		Coord cc = ev.c.add(this.c);
		Coord wsz = MenuSearch.this.csz();
		Coord mrgn = MenuSearch.this.large ? dlmrgn : dsmrgn;
		if(cc.y >= (wsz.y - UI.scale(25) + mrgn.x + mrgn.y + (wsz.x - cc.x)))
		    return(false);
		if(left)
		    toggletree();
		else
		    toggleinfo();
		return(true);
	    }
	    return(super.mousedown(ev));
	}
    }

    protected Deco makedeco() {
	return(new DefaultDeco(this.large) {
		public boolean mouseup(MouseUpEvent ev) {
		    if(szdrag != null) {
			preventResizingOutside();
			preventDraggingOutside();
		    }
		    return(super.mouseup(ev));
		}
	    }.dragsize(true));
    }

    public MenuSearch(String title, MenuGrid menu) {
	super(Coord.z, title);
	this.menu = menu;
	this.treecol = Utils.getprefb(pref_tree, false);
	this.infocol = Utils.getprefb(pref_info, false);
	tree = add(new MenuSearchTree(this, Coord.of(treew, deflisth)));
	treebar = add(new CollapseBar(true));
	fbmsg = add(new Fallback(listw));
	rls = add(new MenuSearchList(this, Coord.of(listw, deflisth)));
	sbox = add(new TextEntry(listw, "") {
		protected void changed() {
		    refilter();
		}

		public void activate(String text) {
		    if(rls.sel != null)
			menu.use(rls.sel.btn, new MenuGrid.Interaction(1, ui.modflags()), false);
		    if(!ui.modctrl) {
			reqclose();
			settext("");
			refilter();
		    }
		}
	    });
	ingbtn = add(new Button(listw, "Search by ingredient", false)
		     .action(() -> menu.wdgmsg("act", "itemcraft")));
	ingbtn.setgkey(kb_itemcraft);
	infobar = add(new CollapseBar(false));
	info = add(new MenuSearchInfo(Coord.of(infow, deflisth)));
	fbmsg.hide();
    }

    public MenuSearch(MenuGrid menu) {
	this("Action search", menu);
    }

    protected Coord defsz() {
	return(Coord.of(treew + barw + listw + barw + infow,
			deflisth + sbox.sz.y + ingbtn.sz.y));
    }

    protected int minw() {
	return((treecol ? 0 : treemin) + barw + listmin + barw + (infocol ? 0 : infomin));
    }

    protected int minh() {
	return(minlisth + sbox.sz.y + ingbtn.sz.y);
    }

    public boolean infocollapsed() {
	return(infocol);
    }

    /* Distribui a área de conteúdo entre as três colunas, proporcionalmente às
     * larguras base. Uma coluna colapsada é escondida e não entra na conta; sua
     * faixa continua lá e é o que resta dela na tela. */
    protected void layout() {
	Coord csz = csz();
	int h = csz.y;
	int listh = h - sbox.sz.y - ingbtn.sz.y - (fbmsg.visible ? elh : 0);
	listh = Math.max(listh, elh);
	int flex = Math.max(csz.x - (barw * 2), listmin);
	/* Cada coluna leva primeiro o seu mínimo; só o excedente é dividido
	 * pelos pesos. Dividir o total pelos pesos violaria o mínimo da coluna
	 * 1, porque 150:250:300 não é a mesma proporção de 90:140:160 -- na
	 * largura mínima a árvore ficaria com 83 dos 90 exigidos. */
	int tmin = treecol ? 0 : treemin, imin = infocol ? 0 : infomin;
	int wsum = listw + (treecol ? 0 : treew) + (infocol ? 0 : infow);
	int surplus = Math.max(flex - (tmin + listmin + imin), 0);
	int tw = treecol ? 0 : (tmin + ((surplus * treew) / wsum));
	int iw = infocol ? 0 : (imin + ((surplus * infow) / wsum));
	/* O resto vai para a lista, para nenhum pixel se perder no
	 * arredondamento. */
	int lw = flex - tw - iw;
	/* A coluna 3 e sua faixa param acima do canto, para o punho de resize
	 * continuar visível. */
	int rh = Math.max(h - sizerh, elh);

	int x = 0;
	if(treecol) {
	    tree.hide();
	} else {
	    tree.show();
	    tree.c = Coord.of(x, 0);
	    tree.resize(Coord.of(tw, h));
	}
	x += tw;

	treebar.c = Coord.of(x, 0);
	treebar.resize(Coord.of(barw, h));
	x += barw;

	int y = 0;
	if(fbmsg.visible) {
	    fbmsg.c = Coord.of(x, y);
	    fbmsg.resize(Coord.of(lw, elh));
	    y += elh;
	}
	rls.c = Coord.of(x, y);
	rls.resize(Coord.of(lw, listh));
	y += listh;
	sbox.c = Coord.of(x, y);
	if(sbox.sz.x != lw)
	    sbox.resize(lw);
	y += sbox.sz.y;
	ingbtn.c = Coord.of(x, y);
	if(ingbtn.sz.x != lw) {
	    ingbtn.resize(lw, ingbtn.sz.y);
	    ingbtn.redraw();
	}
	x += lw;

	infobar.c = Coord.of(x, 0);
	infobar.resize(Coord.of(barw, rh));
	x += barw;

	if(infocol) {
	    info.hide();
	} else {
	    info.show();
	    info.c = Coord.of(x, 0);
	    info.resize(Coord.of(iw, rh));
	}
    }

    public void resize(Coord sz) {
	/* Nenhum caminho conhecido chega aqui antes das colunas existirem --
	 * Window.chdeco usa o resize2() privado, e o construtor de Widget não
	 * chama resize(). Mas resize() é público e sobrescrevível, e layout()
	 * desreferencia todas as colunas: sai barato garantir. */
	if(sbox == null) {
	    super.resize(sz);
	    return;
	}
	sz = Coord.of(Math.max(sz.x, minw()), Math.max(sz.y, minh()));
	super.resize(sz);
	layout();
	Utils.setprefc(pref_sz, sz);
    }

    public void toggletree() {
	Coord csz = csz();
	if(treecol) {
	    treecol = false;
	    resize(Coord.of(csz.x + treerest, csz.y));
	} else {
	    treerest = Math.max(tree.sz.x, treemin);
	    treecol = true;
	    resize(Coord.of(csz.x - treerest, csz.y));
	}
	Utils.setprefb(pref_tree, treecol);
	/* Expandir cresce a janela. Sem isto ela pode passar da borda da tela e
	 * ficar lá até o usuário por acaso arrastar o canto: resize() só limita
	 * pelo mínimo, e preventResizingOutside só roda no fim de um arrasto. */
	preventResizingOutside();
	preventDraggingOutside();
    }

    public void toggleinfo() {
	Coord csz = csz();
	if(infocol) {
	    infocol = false;
	    resize(Coord.of(csz.x + inforest, csz.y));
	} else {
	    inforest = Math.max(info.sz.x, infomin);
	    infocol = true;
	    resize(Coord.of(csz.x - inforest, csz.y));
	}
	Utils.setprefb(pref_info, infocol);
	preventResizingOutside();
	preventDraggingOutside();
    }

    /* Chamado pela árvore. Colapsar a coluna 1 não passa por aqui: a família
     * continua valendo, só deixa de estar visível. */
    public void setscope(Pagina scope) {
	/* A árvore chama isto a cada clique, inclusive no clique que reafirma a
	 * família já selecionada. Sem a guarda, reclicar a linha destacada joga
	 * a lista de volta para o topo -- a rolagem só deve zerar quando o
	 * escopo muda de verdade. */
	if(this.scope == scope)
	    return;
	this.scope = scope;
	rls.scrollval(0);
	refilter();
    }

    /* Chamado pela lista. */
    public void selected(Result item) {
	info.setbtn((item == null) ? null : item.btn);
    }

    private String scopename() {
	Pagina scope = this.scope;
	if(scope == null)
	    return("");
	try {
	    return(scope.button().name());
	} catch(RuntimeException e) {
	    /* Loading é um RuntimeException, então este catch pega os dois
	     * casos, e para o nome de uma família os dois dão no mesmo. */
	    return("...");
	}
    }

    /* Três passos: a lista global, o filtro de família, o fuzzy. Se o resultado
     * vier vazio com família selecionada e texto de busca, repete sem o escopo
     * e avisa. */
    protected void refilter() {
	String text = sbox.text().toLowerCase();
	List<Result> pool = this.cur;
	Pagina scope = this.scope;
	List<Result> scoped;
	if(scope == null) {
	    scoped = pool;
	} else {
	    scoped = new ArrayList<>();
	    for(Result r : pool) {
		if(r.inscope(scope))
		    scoped.add(r);
	    }
	}
	List<Result> found = Fuzzy.fuzzyFilterAndSort(text, scoped);
	boolean fb = MenuSearchLogic.fallback(found.size(), scope != null, !text.isEmpty());
	if(fb) {
	    found = Fuzzy.fuzzyFilterAndSort(text, pool);
	    /* Hífen, não travessão: build.xml não passa -encoding ao javac, então
	     * literais dependem do encoding da plataforma, e nenhum literal do
	     * repositório tem caractere fora de ASCII. */
	    fbmsg.settext("nothing in " + scopename() + " - showing all");
	}
	if(fb != fbmsg.visible) {
	    if(fb)
		fbmsg.show();
	    else
		fbmsg.hide();
	    layout();
	}
	this.filtered = found;
	int idx = filtered.indexOf(rls.sel);
	if((idx < 0) && (rls.sel != null)) {
	    /* Pagina.invalidate() joga fora o PagButton memoizado, e com ele o
	     * Result: a mesma ação volta como objeto novo. Procurar pela pagina,
	     * que sobrevive, evita a seleção pular para o topo sozinha. */
	    Pagina pag = rls.sel.btn.pag;
	    for(int i = 0; i < filtered.size(); i++) {
		if(filtered.get(i).btn.pag == pag) {
		    idx = i;
		    break;
		}
	    }
	    if(idx >= 0)
		rls.change(filtered.get(idx));
	}
	if(idx < 0) {
	    if(filtered.size() > 0) {
		rls.change(filtered.get(0));
		rls.display(0);
	    } else {
		rls.change(null);
	    }
	} else {
	    rls.display(idx);
	}
    }

    protected abstract boolean generate(List<PagButton> buf);

    protected void updlist() {
	recons = false;
	boolean reanc = this.reanc;
	this.reanc = false;
	List<PagButton> buf = new ArrayList<>();
	if(generate(buf))
	    recons = true;
	Map<PagButton, Result> prev = new HashMap<>();
	for(Result pr : this.cur)
	    prev.put(pr.btn, pr);
	List<Result> results = new ArrayList<>();
	for(PagButton btn : buf) {
	    Result pr = prev.get(btn);
	    if(pr == null)
		pr = new Result(btn);
	    else if(reanc)
		pr.reanc();
	    results.add(pr);
	    try {
		pr.ancestors();
	    } catch(Loading l) {
		recons = true;
	    } catch(RuntimeException e) {
		/* Quebrado de vez: não remarca recons, senão a lista seria
		 * reconstruída a cada tick para sempre. inscope() devolve
		 * false para ele. */
	    }
	}
	this.cur = results;
	refilter();
    }

    /* Só quem chama isto é o tick ao ver um pagseq novo. As remarcações por
     * Loading escrevem o campo direto, de propósito: recurso atrasado não
     * reparenteia nada, e refazer a cadeia de ancestrais a cada quadro
     * enquanto os recursos chegam é trabalho jogado fora. */
    protected void recons() {
	recons = true;
	reanc = true;
    }

    public void tick(TickEvent ev) {
	if(ev.visible && recons)
	    updlist();
	super.tick(ev);
    }

    public boolean keydown(KeyDownEvent ev) {
	if(ev.code == ev.awt.VK_DOWN) {
	    int idx = filtered.indexOf(rls.sel);
	    if((idx >= 0) && (idx < filtered.size() - 1)) {
		idx++;
		rls.change(filtered.get(idx));
		rls.display(idx);
	    }
	    return(true);
	} else if(ev.code == ev.awt.VK_UP) {
	    int idx = filtered.indexOf(rls.sel);
	    if(idx > 0) {
		idx--;
		rls.change(filtered.get(idx));
		rls.display(idx);
	    }
	    return(true);
	} else {
	    return(super.keydown(ev));
	}
    }

    public static class Main extends MenuSearch {
	private int pagseq;

	public Main(MenuGrid menu) {
	    super(menu);
	    pagseq = menu.pagseq;
	    resize(Utils.getprefc(pref_sz, defsz()));
	}

	/* Sempre global: o escopo de família é um predicado dentro de
	 * refilter(), então trocar de família não reconstrói a lista a partir
	 * de menu.paginae. */
	protected boolean generate(List<PagButton> buf) {
	    boolean recons = false;
	    Collection<Pagina> leaves = new ArrayList<>();
	    synchronized(menu.paginae) {
		leaves.addAll(menu.paginae);
	    }
	    for(Pagina pag : leaves) {
		try {
		    buf.add(pag.button());
		} catch(Loading l) {
		    recons = true;
		} catch(RuntimeException e) {
		    /* Ação com recurso quebrado simplesmente não aparece na
		     * busca. Sem recons: ela não vai consertar sozinha. */
		    Warning.warn("action search: dropping a broken pagina: %s", e);
		}
	    }
	    Collections.sort(buf, Comparator.comparing(PagButton::name));
	    return(recons);
	}

	public void tick(TickEvent ev) {
	    if(ev.visible) {
		if(pagseq != menu.pagseq) {
		    recons();
		    tree.recons();
		    pagseq = menu.pagseq;
		}
	    }
	    super.tick(ev);
	}
    }
}
