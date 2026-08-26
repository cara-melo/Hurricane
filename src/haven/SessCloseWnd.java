package haven;

/* "Log out X?" antes de derrubar a sessão de uma aba. Uma por vez: fechar por
 * engano um alt que estava no meio do mato é justamente o que isto evita. */
public class SessCloseWnd extends Window {
    private final SessionTab tab;

    public static void open(UI ui, SessionTab tab) {
	if(ui.root.findchild(SessCloseWnd.class) != null)
	    return;
	SessCloseWnd wnd = new SessCloseWnd(tab);
	ui.root.add(wnd, ui.root.sz.sub(wnd.sz).div(2));
    }

    private SessCloseWnd(SessionTab tab) {
	super(UI.scale(new Coord(260, 80)), "Close session");
	this.tab = tab;
	add(new Label("Log out " + tab.display() + "?"), UI.scale(new Coord(10, 10)));
	add(new Button(UI.scale(90), "Log out") {
		public void click() {
		    SessCloseWnd.this.tab.close();
		    SessCloseWnd.this.reqdestroy();
		}
	    }, UI.scale(new Coord(15, 45)));
	add(new Button(UI.scale(90), "Cancel") {
		public void click() {
		    SessCloseWnd.this.reqdestroy();
		}
	    }, UI.scale(new Coord(150, 45)));
    }

    public void wdgmsg(Widget sender, String msg, Object... args) {
	if((sender == this) && msg.equals("close"))
	    reqdestroy();
	else
	    super.wdgmsg(sender, msg, args);
    }
}
