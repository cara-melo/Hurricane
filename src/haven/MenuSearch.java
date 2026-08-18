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

    /* Larguras das colunas 1 e 2. Não são mais pesos: cada uma é a largura fixa
     * que a coluna tem até o usuário arrastar a divisória. `infow` sobrou só
     * para o tamanho padrão da janela, porque a coluna 3 é sempre o resto.
     *
     * A árvore vale 250 e não os 150 de antes por medição: dos 93 nomes de
     * família do jogo, metade passa de 150 e nenhum passa de 246. */
    public static final int treew = UI.scale(250), listw = UI.scale(250), infow = UI.scale(300);
    public static final int treemin = UI.scale(90), listmin = UI.scale(140), infomin = UI.scale(160);
    /* A faixa entre duas colunas. Quando a coluna ao lado está colapsada ela é
     * tudo o que sobra dela, que é a faixa clicável de 15 do design. */
    public static final int barw = UI.scale(15);
    /* A tampa de colapso, no topo da faixa: uma linha da lista de altura. O
     * resto da faixa é zona de arrasto, e as duas não se misturam para não
     * haver missclick entre colapsar e redimensionar. */
    public static final int caph = elh;
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
    public static final String pref_treew = "srch-tree-w";
    public static final String pref_listw = "srch-list-w";

    public static final KeyBinding kb_itemcraft = KeyBinding.get("scm-itemcraft", KeyMatch.nil);

    public final MenuGrid menu;
    public final MenuSearchTree tree;
    public final MenuSearchList rls;
    public final MenuSearchInfo info;
    public final SplitBar treebar, infobar;
    public final Fallback fbmsg;
    public final TextEntry sbox;
    public final Button ingbtn;

    protected List<Result> cur = Collections.emptyList();
    protected List<Result> filtered = Collections.emptyList();
    protected Pagina scope = null;
    private boolean recons = true;
    private boolean reanc = false;
    private boolean treecol, infocol;
    /* As larguras fixas das colunas 1 e 2, em pixels já escalados. Só mudam por
     * arrasto da divisória, e sobrevivem à sessão. */
    private int treefix = treew, listfix = listw;
    /* Quanto a janela encolheu ao colapsar cada coluna, devolvido ao expandir.
     * Não é a largura escolhida pelo usuário: numa janela apertada a cascata de
     * `widths` mostra a árvore mais estreita que `treefix`, e é essa largura
     * menor que tem que voltar, senão o par colapsar-expandir alarga a janela
     * sozinho. `treefix` continua guardando a escolha do arrasto. */
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

    /* A faixa entre duas colunas. A tampa no topo colapsa e expande; o corpo
     * abaixo dela arrasta a divisória. As duas zonas não se sobrepõem: um
     * clique só colapsa se cair na tampa, e um arrasto só redimensiona se
     * começar no corpo.
     *
     * Com a coluna colapsada não existe divisória para arrastar -- a faixa é o
     * que sobrou da coluna na tela -- então ela inteira vira o botão de
     * expandir, que é a ação mais difícil de descobrir e merece o alvo maior. */
    public class SplitBar extends Widget {
	public final boolean left;
	private UI.Grab grab = null;
	private Coord start = null;
	private boolean dragging = false;

	public SplitBar(boolean left) {
	    super(Coord.of(barw, elh));
	    this.left = left;
	}

	private boolean collapsed() {
	    return(left ? treecol : infocol);
	}

	public void draw(GOut g) {
	    boolean col = collapsed();
	    g.chcolor(60, 50, 34, 255);
	    g.frect2(Coord.z, sz);
	    if(!col) {
		/* A tampa fica um tom acima do corpo, com um fio embaixo: as
		 * duas zonas fazem coisas diferentes e não podem parecer a
		 * mesma superfície. */
		g.chcolor(82, 69, 47, 255);
		g.frect2(Coord.z, Coord.of(sz.x, caph));
		g.chcolor(38, 32, 22, 255);
		g.line(Coord.of(0, caph), Coord.of(sz.x, caph), 1.0);
	    }
	    g.chcolor(200, 180, 140, 255);
	    /* A seta aponta para onde o clique vai mover a coluna. */
	    boolean pointleft = (left != col);
	    int mx = sz.x / 2, my = col ? (sz.y / 2) : (caph / 2), a = UI.scale(4);
	    int tip = pointleft ? (mx - (a / 2)) : (mx + (a / 2));
	    int tail = pointleft ? (mx + (a / 2)) : (mx - (a / 2));
	    g.line(Coord.of(tail, my - a), Coord.of(tip, my), 1.0);
	    g.line(Coord.of(tip, my), Coord.of(tail, my + a), 1.0);
	    if(!col) {
		/* Três pontos no centro vertical da zona de arrasto: sem eles a
		 * faixa não avisa que dá para pegá-la. */
		g.chcolor(150, 132, 100, 255);
		int u = UI.scale(1), cy = caph + ((sz.y - caph) / 2), d = UI.scale(5);
		for(int i = -1; i <= 1; i++)
		    g.frect2(Coord.of(mx - u, (cy + (i * d)) - u),
			     Coord.of(mx + u, (cy + (i * d)) + u));
	    }
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
		if(collapsed() || (ev.c.y < caph)) {
		    if(left)
			toggletree();
		    else
			toggleinfo();
		    return(true);
		}
		/* O grab não é o que traz os movimentos: o filtro de
		 * UI.grabmouse (UI.java:596-600) passa só down, up, wheel e
		 * CursorQuery, e MouseMoveEvent.propagation (Widget.java:1060)
		 * faz broadcast para todo filho visível, sem hit test, então a
		 * faixa recebe o movimento estando ou não sob o ponteiro. O grab
		 * serve para o mouseup, esse sim testado contra a área do widget,
		 * e para os vizinhos não receberem clique no meio do arrasto. Sem
		 * ele o botão solto fora da faixa deixaria o arrasto preso. */
		start = ev.c;
		dragging = false;
		grab = ui.grabmouse(this);
		return(true);
	    }
	    return(super.mousedown(ev));
	}

	public void mousemove(MouseMoveEvent ev) {
	    if(grab != null) {
		if(!dragging && (ev.c.dist(start) > UI.scale(3)))
		    dragging = true;
		/* `ev.c.x + this.c.x` é a posição absoluta do ponteiro na área
		 * de conteúdo, e `start.x` é onde dentro da faixa o botão foi
		 * apertado. Subtrair um do outro dá a borda esquerda da faixa
		 * como o usuário a segurou: sem isso a divisória pularia para
		 * debaixo do ponteiro no instante em que o limiar é cruzado, até
		 * 14 escalados de salto. Como `start.x` é fixo durante o arrasto
		 * e a posição absoluta não depende de onde a faixa foi parar, o
		 * valor é função pura do ponteiro -- a divisória fica presa no
		 * batente enquanto o ponteiro estiver além dele e solta no ponto
		 * exato em que ele volta. */
		if(dragging)
		    MenuSearch.this.dragbar(left, ev.c.x + this.c.x - start.x);
	    }
	    super.mousemove(ev);
	}

	public boolean mouseup(MouseUpEvent ev) {
	    if((ev.b == 1) && (grab != null)) {
		grab.remove();
		grab = null;
		if(dragging)
		    savewidths();
		dragging = false;
		start = null;
		return(true);
	    }
	    return(super.mouseup(ev));
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
	this.treefix = Utils.getprefi(pref_treew, treew);
	this.listfix = Utils.getprefi(pref_listw, listw);
	tree = add(new MenuSearchTree(this, Coord.of(treew, deflisth)));
	treebar = add(new SplitBar(true));
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
	infobar = add(new SplitBar(false));
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
	int flex = Math.max(csz.x - (barw * 2), 0);
	int[] cw = MenuSearchLogic.widths(flex, treefix, listfix, treemin, listmin, infomin, treecol, infocol);
	int tw = cw[0], lw = cw[1], iw = cw[2];
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

    private static int clamp(int v, int min, int max) {
	return(Math.max(min, Math.min(v, Math.max(min, max))));
    }

    /* Chamado pela faixa enquanto ela é arrastada. `cx` é a posição do lado
     * esquerdo da faixa em coordenadas da área de conteúdo: a divisória segue o
     * ponteiro. O limite superior é o que sobra depois dos mínimos das outras
     * colunas visíveis, para um arrasto largo nunca espremer a coluna 3 abaixo
     * do mínimo dela. */
    public void dragbar(boolean left, int cx) {
	int flex = Math.max(csz().x - (barw * 2), 0);
	if(left) {
	    treefix = clamp(cx, treemin, flex - listmin - (infocol ? 0 : infomin));
	} else {
	    int[] cw = MenuSearchLogic.widths(flex, treefix, listfix, treemin, listmin, infomin, treecol, infocol);
	    listfix = clamp(cx - cw[0] - barw, listmin, flex - cw[0] - infomin);
	}
	layout();
    }

    /* Só no fim do arrasto: gravar a cada pixel escreveria a preferência
     * dezenas de vezes por segundo. */
    protected void savewidths() {
	Utils.setprefi(pref_treew, treefix);
	Utils.setprefi(pref_listw, listfix);
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
	    /* Guarda o encolhimento, não a escolha do usuário: `treefix` é a
	     * largura arrastada e sobrevive a um colapso feito com a janela
	     * apertada, quando a árvore na tela está mais estreita que ele. */
	    treerest = tree.sz.x;
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
