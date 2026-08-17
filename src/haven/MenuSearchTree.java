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
import haven.MenuGrid.Pagina;
import haven.MenuSearchLogic.Node;

/* Coluna 1 da janela de busca de ações: a árvore de famílias, achatada numa
 * lista das linhas visíveis para reusar a rolagem e a seleção que SListBox já
 * tem, em vez de introduzir um widget de árvore novo.
 *
 * As raízes saem da própria árvore do menu -- as que o servidor manda mais as
 * que MenuGrid.loadCustomActionButtons injeta -- e não de uma lista curada,
 * para que uma categoria nova apareça sozinha. */
public class MenuSearchTree extends SListBox<Node<Pagina>, Widget> {
    public static final int indent = UI.scale(12);
    public static final int arroww = UI.scale(14);

    public final MenuSearch wnd;
    /* O nó "All", sem pagina: escopo nulo. */
    private final Node<Pagina> all = new Node<Pagina>(null, "All");
    private final Set<Pagina> expanded = new HashSet<>();
    private List<Node<Pagina>> roots = new ArrayList<>();
    private List<Node<Pagina>> rows = new ArrayList<>();
    private boolean recons = true;

    public MenuSearchTree(MenuSearch wnd, Coord sz) {
	super(sz, MenuSearch.elh);
	this.wnd = wnd;
	/* Direto no campo, e não via change(): o resto da janela ainda não
	 * existe quando a árvore é construída. */
	this.sel = all;
	this.rows = new ArrayList<>(Collections.singletonList(all));
    }

    protected List<Node<Pagina>> items() {return(rows);}

    public void recons() {
	recons = true;
    }

    private void rebuild() {
	recons = false;
	Collection<Pagina> leaves = new ArrayList<>();
	synchronized(wnd.menu.paginae) {
	    leaves.addAll(wnd.menu.paginae);
	}
	MenuSearchLogic.Tree<Pagina> tree =
	    MenuSearchLogic.tree(leaves, Pagina::parent, p -> p.button().name());
	if(!tree.complete)
	    recons = true;
	this.roots = tree.roots;
	Set<Pagina> live = new HashSet<>();
	collect(tree.roots, live);
	expanded.retainAll(live);
	Pagina id = (this.sel == null) ? null : this.sel.id;
	reflow();
	/* rows é reconstruída a cada rebuild, então o objeto Node selecionado
	 * fica obsoleto. Se a família continua visível, troca só a identidade do
	 * Node e o escopo segue igual. Se sumiu -- um pagseq novo pode
	 * aposentá-la -- volta para "All" por change(), nunca atribuindo sel
	 * direto: sem o change() a janela continuaria filtrando por uma família
	 * que não está destacada em lugar nenhum, que é exatamente a "lista
	 * vazia sem explicação" que se quer evitar. */
	Node<Pagina> now = find(id);
	if(now != null)
	    this.sel = now;
	else if(tree.complete)
	    change(all);
	/* Se a passada veio incompleta, sel fica apontando para um Node que não
	 * está mais em rows: nada destacado por um ou dois quadros, mas o escopo
	 * preservado. Resetar aqui perderia a família do usuário só porque o
	 * recurso dela estava sendo rebaixado naquele instante. */
    }

    private Node<Pagina> find(Pagina id) {
	for(Node<Pagina> n : rows) {
	    if(n.id == id)
		return(n);
	}
	return(null);
    }

    private void collect(List<Node<Pagina>> nodes, Set<Pagina> out) {
	for(Node<Pagina> n : nodes) {
	    out.add(n.id);
	    collect(n.sub, out);
	}
    }

    private void reflow() {
	List<Node<Pagina>> rows = new ArrayList<>();
	rows.add(all);
	rows.addAll(MenuSearchLogic.flatten(roots, expanded::contains));
	this.rows = rows;
    }

    private String label(Node<Pagina> el) {
	String mark = el.sub.isEmpty() ? "  " : (expanded.contains(el.id) ? "- " : "+ ");
	return(mark + el.name);
    }

    protected Widget makeitem(Node<Pagina> el, int idx, Coord sz) {
	return(new SListWidget.TextItem(sz) {
		protected String text() {return(label(el));}
		protected int margin() {return(el.depth * indent);}
		protected Text.Forge foundry() {return(MenuSearch.elf);}
		/* Re-renderiza quando o marcador +/- muda. */
		protected boolean valid(String text) {return(text.equals(label(el)));}
	    });
    }

    /* Os itens são TextItem, que não trata mousedown, então o clique cai aqui:
     * em cima do marcador expande ou recolhe, no resto da linha seleciona a
     * família. */
    protected boolean slotclick(Coord c, int slot, int button) {
	if(button != 1)
	    return(false);
	List<Node<Pagina>> rows = this.rows;
	if((slot < 0) || (slot >= rows.size()))
	    return(false);
	Node<Pagina> n = rows.get(slot);
	int x0 = n.depth * indent;
	if(!n.sub.isEmpty() && (c.x >= x0) && (c.x < (x0 + arroww))) {
	    if(!expanded.remove(n.id))
		expanded.add(n.id);
	    reflow();
	    /* Recolher um ramo esconde a família selecionada, se ela estava
	     * dentro dele. Sem isto o escopo continuaria valendo com nenhuma
	     * linha destacada. Selecionar o ramo recolhido é o que o usuário
	     * espera: o escopo alarga em vez de sumir.
	     *
	     * this.rows, não a cópia local acima: reflow() acabou de trocar o
	     * campo, e a local ficou apontando para a lista antiga. */
	    if(!this.rows.contains(this.sel))
		change(n);
	    return(true);
	}
	change(n);
	return(true);
    }

    /* Clique fora das linhas não deve limpar a seleção: sem família selecionada
     * não existe estado -- "All" é uma linha como as outras. */
    protected boolean unselect(int button) {
	return(true);
    }

    public void change(Node<Pagina> item) {
	if(item == null)
	    item = all;
	super.change(item);
	wnd.setscope(item.id);
    }

    public void tick(double dt) {
	if(recons)
	    rebuild();
	super.tick(dt);
    }
}
