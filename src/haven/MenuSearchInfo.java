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

import java.awt.Color;
import java.awt.image.BufferedImage;
import haven.MenuGrid.PagButton;

/* Coluna 3 da janela de busca de ações: descrição e insumos da ação
 * selecionada. Recebe um PagButton ou null e desenha; não conhece a lista nem
 * a árvore.
 *
 * O corpo das janelas do cliente é translúcido -- Window.DefaultDeco.drawbg
 * empilha gfx/hud/wnd/lg/bg sobre o mundo, e o mundo aparece através dele --
 * então o texto precisa de contraste próprio. O tooltip resolve isso com
 * PUtils.strokeImg, que custa de 1 a 6 ms por reconstrução. Aqui o problema é
 * resolvido com um retângulo opaco atrás do painel, o que deixa a
 * reconstrução em 0,05 a 0,35 ms e torna o resize em tempo real viável.
 *
 * O conteúdo reflui na largura, mas não rola: uma ação com descrição muito
 * longa numa janela baixa é cortada embaixo. Rolagem está fora do escopo do
 * design; a saída do usuário é aumentar a altura da janela. */
public class MenuSearchInfo extends Widget {
    public static final Color bg = new Color(20, 17, 13, 255);
    public static final Color frame = new Color(74, 61, 40, 255);
    public static final int marg = UI.scale(4);

    private PagButton cur = null;
    private Tex tex = null;
    private PagButton texbtn = null;
    private int texw = -1;

    public MenuSearchInfo(Coord sz) {
	super(sz);
    }

    public void setbtn(PagButton btn) {
	this.cur = btn;
    }

    /* O cache é obrigatório mesmo com a janela parada: draw() roda a cada
     * frame. A chave é (item, largura) porque a largura decide a quebra de
     * linha do título. */
    private Tex render(int w) {
	PagButton btn = this.cur;
	if(btn == null)
	    return(null);
	if((texbtn == btn) && (texw == w))
	    return(tex);
	try {
	    BufferedImage img = btn.rendertt(true, w, false);
	    if(tex != null)
		tex.dispose();
	    tex = new TexI(img);
	    texbtn = btn;
	    texw = w;
	} catch(Loading l) {
	    /* Frequente: info() e res.layer() disparam enquanto os recursos
	     * ainda chegam. Mantém o que está desenhado e tenta de novo no
	     * próximo frame, já que texbtn continua diferente de cur. */
	} catch(RuntimeException e) {
	    /* Recurso quebrado, não recurso atrasado: LoadFailedException e
	     * NoSuchResourceException descendem de RuntimeException, não de
	     * Loading, e sobem daqui até o laço de render, que não pega nada e
	     * congela o cliente. O MenuSearch de hoje pega Exception no
	     * tooltip pelo mesmo motivo.
	     *
	     * Marca o botão como resolvido com painel vazio: sem isto o quadro
	     * seguinte tentaria de novo, para sempre. */
	    if(tex != null)
		tex.dispose();
	    tex = null;
	    texbtn = btn;
	    texw = w;
	}
	return(tex);
    }

    public void draw(GOut g) {
	g.chcolor(bg);
	g.frect2(Coord.z, sz);
	g.chcolor(frame);
	g.rect2(Coord.z, sz.sub(1, 1));
	g.chcolor();
	Coord isz = sz.sub(marg * 2, marg * 2);
	if(cur == null) {
	    /* Nada selecionado: a textura que sobrou não vai ser desenhada de
	     * novo, e a janela pode ficar aberta assim por muito tempo. */
	    if(tex != null) {
		tex.dispose();
		tex = null;
		texbtn = null;
	    }
	} else if((isz.x > 0) && (isz.y > 0)) {
	    Tex tex = render(Math.max(isz.x, UI.scale(40)));
	    if(tex != null)
		g.reclip(Coord.of(marg), isz).image(tex, Coord.z);
	}
	/* Fora do if: hoje não há filhos, mas o dia que houver -- uma barra de
	 * rolagem -- eles não podem sumir só porque nada está selecionado. */
	super.draw(g);
    }

    public void dispose() {
	super.dispose();
	if(tex != null) {
	    tex.dispose();
	    tex = null;
	}
    }
}
