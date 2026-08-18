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
import java.util.function.*;

/* A lógica da janela de busca de ações que não depende de widget nenhum. É
 * genérica sobre o tipo do nó -- a janela instancia com MenuGrid.Pagina, o
 * teste com String -- porque Pagina é uma classe aninhada de MenuGrid, e
 * carregar MenuGrid fora do cliente morre no <clinit> de Window. */
public class MenuSearchLogic {
    /* Um ciclo em parent() travaria as caminhadas abaixo. A árvore real tem
     * dois ou três níveis; isto é só um limite de segurança. Num ciclo o ramo
     * inteiro some da árvore em vez de aparecer torto -- deliberado: marcar a
     * árvore como incompleta faria o cliente remontá-la a cada tick para
     * sempre, e MenuGrid.cons() sequer se defende do caso. */
    public static final int maxdepth = 32;

    public static class Node<T> {
	public final T id;
	public final String name;
	public final List<Node<T>> sub = new ArrayList<>();
	public int depth = 0;

	public Node(T id, String name) {
	    this.id = id;
	    this.name = name;
	}
    }

    public static class Tree<T> {
	public final List<Node<T>> roots;
	/* false quando algum parent() ou name() ainda estava carregando, o que
	 * significa que a árvore deve ser remontada no próximo tick. */
	public final boolean complete;

	public Tree(List<Node<T>> roots, boolean complete) {
	    this.roots = roots;
	    this.complete = complete;
	}
    }

    /* Monta a árvore de famílias de baixo para cima a partir das folhas
     * descobertas, como MenuGrid.cons faz. Família é todo nó que é pai de
     * alguma coisa alcançável; as próprias folhas nunca viram nó, então um ramo
     * sem nenhuma ação alcançável simplesmente não existe. */
    public static <T> Tree<T> tree(Collection<T> leaves, Function<T, T> parent, Function<T, String> name) {
	Map<T, Node<T>> nodes = new LinkedHashMap<>();
	Map<T, T> up = new HashMap<>();
	/* Nós cujo parent() chegou até o fim. Sem isto, um nó cuja consulta de
	 * pai morreu em Loading fica sem entrada em `up` e viraria raiz, porque
	 * "sem pai" e "pai ainda desconhecido" seriam a mesma coisa. */
	Set<T> resolved = new HashSet<>();
	boolean complete = true;
	for(T leaf : leaves) {
	    try {
		int d = 0;
		for(T at = parent.apply(leaf); (at != null) && (d < maxdepth); d++) {
		    if(!nodes.containsKey(at))
			nodes.put(at, new Node<T>(at, name.apply(at)));
		    T pa = parent.apply(at);
		    if(pa != null)
			up.put(at, pa);
		    resolved.add(at);
		    at = pa;
		}
	    } catch(Loading l) {
		complete = false;
	    } catch(RuntimeException e) {
		/* Recurso quebrado, não recurso atrasado: LoadFailedException
		 * não descende de Loading, e daqui sobe até o tick, que não
		 * pega nada e congela a UI inteira. Descarta esta folha sem
		 * marcar incompleta -- um recurso quebrado não conserta
		 * sozinho, e remarcar faria a árvore ser remontada a cada
		 * tick para sempre. */
	    }
	}
	List<Node<T>> roots = new ArrayList<>();
	for(Map.Entry<T, Node<T>> ent : nodes.entrySet()) {
	    T pa = up.get(ent.getKey());
	    if(pa == null) {
		/* Um nó sem pai só é raiz se sabemos que ele não tem pai. Se a
		 * consulta ficou pela metade ele fica fora da árvore até o
		 * rebuild que `complete == false` já pediu. */
		if(resolved.contains(ent.getKey()))
		    roots.add(ent.getValue());
		continue;
	    }
	    Node<T> pn = nodes.get(pa);
	    if(pn != null)
		pn.sub.add(ent.getValue());
	}
	sort(roots, 0);
	return(new Tree<T>(roots, complete));
    }

    private static <T> void sort(List<Node<T>> nodes, int depth) {
	Collections.sort(nodes, (a, b) -> a.name.compareTo(b.name));
	for(Node<T> n : nodes) {
	    n.depth = depth;
	    sort(n.sub, depth + 1);
	}
    }

    /* As linhas visíveis, em ordem, dado quais nós estão expandidos. */
    public static <T> List<Node<T>> flatten(List<Node<T>> roots, Predicate<T> expanded) {
	List<Node<T>> out = new ArrayList<>();
	flatten(out, roots, expanded);
	return(out);
    }

    private static <T> void flatten(List<Node<T>> out, List<Node<T>> nodes, Predicate<T> expanded) {
	for(Node<T> n : nodes) {
	    out.add(n);
	    if(!n.sub.isEmpty() && expanded.test(n.id))
		flatten(out, n.sub, expanded);
	}
    }

    /* Todo nó de `leaf` até a raiz, incluindo a própria `leaf`, para que o
     * teste de escopo seja um contains em vez de uma caminhada por tecla
     * digitada.
     *
     * Propaga Loading, ao contrário de tree(): quem chama decide por item se
     * um item ainda não carregado fica de fora do escopo ou força um rebuild. */
    public static <T> Set<T> ancestors(T leaf, Function<T, T> parent) {
	Set<T> ret = new HashSet<>();
	int d = 0;
	for(T at = leaf; (at != null) && (d < maxdepth); at = parent.apply(at), d++)
	    ret.add(at);
	return(ret);
    }

    /* Se uma busca com escopo que não achou nada deve ser refeita globalmente.
     * Sem texto não há fallback: aí o usuário está navegando, não buscando. */
    public static boolean fallback(int scopedcount, boolean hasscope, boolean hastext) {
	return((scopedcount == 0) && hasscope && hastext);
    }

    /* Distribui a largura de conteúdo, já descontadas as duas faixas, entre as
     * três colunas.
     *
     * Colunas 1 e 2 têm largura fixa, escolhida pelo arrasto da divisória, e a
     * coluna 3 fica com o resto. É a única das três que converte largura em
     * conteúdo: a lista mostra todos os nomes de ação do jogo com 241 escalados
     * e a árvore todas as famílias com 246, enquanto a descrição reflui e não
     * rola, então largura extra ali é a única defesa contra o texto ser cortado
     * embaixo. Dividir o excedente pelos três, como fazia a versão de pesos,
     * entrega espaço morto para as duas primeiras.
     *
     * Com a coluna 3 colapsada quem absorve é a lista: alguém tem que ficar com
     * a sobra, e ela é a única elástica que restou.
     *
     * Ao encolher, a cascata é 3, depois lista, depois árvore -- do mais
     * elástico para o menos. Abaixo da soma dos três mínimos a coluna 3 fica
     * pequena em vez de negativa; a janela não chega lá, porque MenuSearch.minw()
     * a barra antes. */
    public static int[] widths(int flex, int treefix, int listfix,
			       int treemin, int listmin, int infomin,
			       boolean treecol, boolean infocol) {
	int tw = treecol ? 0 : Math.max(treefix, treemin);
	int lw = Math.max(listfix, listmin);
	if(infocol) {
	    lw = flex - tw;
	    if(lw < listmin) {
		tw = Math.max(tw - (listmin - lw), treecol ? 0 : treemin);
		lw = Math.max(flex - tw, listmin);
	    }
	    return(new int[] {Math.max(tw, 0), Math.max(lw, 0), 0});
	}
	int iw = flex - tw - lw;
	if(iw < infomin) {
	    int need = infomin - iw;
	    int give = Math.min(need, lw - listmin);
	    lw -= give;
	    need -= give;
	    give = Math.min(need, tw - (treecol ? 0 : treemin));
	    tw -= give;
	    iw = flex - tw - lw;
	}
	return(new int[] {Math.max(tw, 0), Math.max(lw, 0), Math.max(iw, 0)});
    }
}
