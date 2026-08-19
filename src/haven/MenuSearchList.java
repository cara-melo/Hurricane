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
import java.awt.image.BufferedImage;
import haven.UI.Grab;
import haven.MenuGrid.Interaction;
import haven.MenuSearch.Result;

/* Coluna 2 da janela de busca de ações: a lista filtrada, o arrasto para a
 * barra de ação e o tooltip flutuante. O estado do arrasto (drag_start,
 * drag_mode, grab) morava em MenuSearch apesar de só os itens da lista o
 * usarem; aqui ele fica junto de quem o usa. */
public class MenuSearchList extends SListBox<Result, Widget> {
    public final MenuSearch wnd;
    private Coord drag_start = null;
    private boolean drag_mode = false;
    private Grab grab = null;
    /* Quem apertou o botão, e sobre o quê. O arrasto não pode se apoiar em
     * sel: refilter() roda a cada tecla, e uma tecla digitada com o botão
     * ainda apertado destrói o widget da linha e move sel para outro item --
     * soltar acabaria usando a ação errada. */
    private Widget dragwdg = null;
    private Result dragitem = null;

    private void enddrag() {
	drag_start = null;
	drag_mode = false;
	dragwdg = null;
	dragitem = null;
	if(grab != null) {
	    grab.remove();
	    grab = null;
	}
    }

    public MenuSearchList(MenuSearch wnd, Coord sz) {
	super(sz, MenuSearch.elh);
	this.wnd = wnd;
    }

    protected List<Result> items() {return(wnd.filtered);}

    protected Widget makeitem(Result el, int idx, Coord sz) {
	return(new ItemWidget<Result>(this, sz, el) {
		{
		    add(new IconText(sz) {
			    protected BufferedImage img() {
				try {
				    return(item.btn.img());
				} catch(Loading l) {
				    /* Ainda chegando: deixa subir para o
				     * drawicon de SListWidget, que desenha o
				     * placeholder e tenta de novo. */
				    throw(l);
				} catch(RuntimeException e) {
				    /* Quebrado de vez. drawicon só pega
				     * Loading, e daqui isto subiria até o laço
				     * de render e congelaria o cliente. null
				     * vira Tex.nil: a linha fica sem ícone. */
				    return(null);
				}
			    }

			    protected String text() {
				try {
				    return(el.btn.name());
				} catch(Loading l) {
				    throw(l);
				} catch(RuntimeException e) {
				    return("???");
				}
			    }

			    protected int margin() {return(0);}
			    protected Text.Foundry foundry() {return(MenuSearch.elf);}
			}, Coord.z);
		}

		public boolean mousedown(MouseDownEvent ev) {
		    super.mousedown(ev);
		    if(ev.b == 1) {
			enddrag();
			drag_start = ui.mc;
			dragwdg = this;
			dragitem = this.item;
			grab = ui.grabmouse(this);
		    }
		    return(true);
		}

		public void mousemove(MouseMoveEvent ev) {
		    if(!drag_mode && (dragwdg == this) && (drag_start != null) && (drag_start.dist(ui.mc) > 40))
			drag_mode = true;
		    super.mousemove(ev);
		}

		public boolean mouseup(MouseUpEvent ev) {
		    if((ev.b == 1) && (dragwdg == this)) {
			/* Do item que foi apertado, não de sel. */
			Result item = dragitem;
			boolean drag = drag_mode;
			enddrag();
			if(drag)
			    DropTarget.dropthing(ui.root, ui.mc, item.btn.pag);
			else
			    wnd.menu.use(item.btn, new Interaction(), false);
			setfocus(ui.gui.portrait); // ND: do this to defocus the search box after you select something. It's focusing on your portrait, which does nothing.
		    }
		    return(super.mouseup(ev));
		}

		public void destroy() {
		    /* refilter() destrói as linhas que saíram do filtro. Se a
		     * linha que segurava o arrasto morreu, o arrasto morre com
		     * ela, em vez de deixar estado pendurado. */
		    if(dragwdg == this)
			enddrag();
		    super.destroy();
		}
	    });
    }

    public void change(Result item) {
	super.change(item);
	wnd.selected(item);
    }

    /* Com a coluna 3 aberta o tooltip seria a mesma informação duas vezes na
     * tela, então ele só existe quando ela está colapsada. Continua com
     * contorno e 300 de largura: ele flutua sobre o mundo, não sobre um fundo
     * opaco como o painel.
     *
     * tooltip() é consultado a cada frame enquanto o cursor não sai da linha, e
     * o caminho com contorno custa de 1 a 6 ms. O cache de um item é o mesmo
     * que MenuGrid.tooltip mantém em curtt/curttp (MenuGrid.java:518-537); sem
     * ele, cada frame de hover refaz o strokeImg e vaza uma textura. */
    private MenuGrid.PagButton curttp = null;
    private Tex curtt = null;

    public Object tooltip(Coord c, Widget prev) {
	if(!wnd.infocollapsed())
	    return(null);
	List<Result> items = items();
	int slot = slotat(c);
	if((slot < 0) || (slot >= items.size()))
	    return(null);
	Result item = items.get(slot);
	if(item == null)
	    return(null);
	if(item.btn != curttp) {
	    try {
		BufferedImage ti = item.btn.rendertt(true);
		if(curtt != null)
		    curtt.dispose();
		curtt = (ti == null) ? null : new TexI(ti);
		curttp = item.btn;
	    } catch(Loading l) {
		/* Ainda chegando: sem tooltip neste quadro, tenta de novo no
		 * próximo porque curttp continua diferente. */
		return(null);
	    } catch(RuntimeException e) {
		/* Recurso quebrado. LoadFailedException não descende de
		 * Loading, e daqui sobe até o laço de render, que congela o
		 * cliente -- é por isso que o tooltip de hoje pega Exception.
		 * Marca como resolvido para não repetir a cada quadro. */
		if(curtt != null)
		    curtt.dispose();
		curtt = null;
		curttp = item.btn;
		return(null);
	    }
	}
	return(curtt);
    }

    public void dispose() {
	super.dispose();
	if(curtt != null) {
	    curtt.dispose();
	    curtt = null;
	}
	curttp = null;
    }

    public void draw(GOut g) {
	super.draw(g);
	if(drag_mode && (dragitem != null)) {
	    GSprite ds;
	    try {
		ds = dragitem.btn.spr();
	    } catch(RuntimeException e) {
		/* Sprite ainda chegando, ou quebrada. Sem fantasma neste
		 * quadro; deixar subir congelaria o cliente. */
		return;
	    }
	    ui.drawafter(new UI.AfterDraw() {
		    public void draw(GOut g) {
			ds.draw(g.reclip(ui.mc.sub(ds.sz().div(2)), ds.sz()));
		    }
		});
	}
    }
}
