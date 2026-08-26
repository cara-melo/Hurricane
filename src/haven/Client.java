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

import java.sql.SQLException;
import java.util.*;
import java.io.*;
import java.nio.file.*;

import haven.automated.helpers.HitBoxes;
import haven.render.*;
import haven.iosys.*;
import haven.iosys.tk.*;
import haven.iosys.audio.*;
import java.awt.image.BufferedImage;

public class Client implements Console.Directory {
    public static final Config.Variable<Boolean> initfullscreen = Config.Variable.propb("haven.fullscreen", false);
    public final Toolkit tk;
    public final Windeye wnd;
    private final EventQueue queue = new EventQueue(this);
    private UILoop loop;
    private Thread mt;
    public final SessionSet sessions = new SessionSet(new SessionSet.Host() {
	    public void focus(SessionTab tab) {Client.this.focus(tab);}
	    public void spawn() {Client.this.spawn();}
	    public void closed(SessionTab tab) {
		UILoop loop = Client.this.loop;
		if(loop != null)
		    loop.discard(tab);
	    }
	    public void empty() {Client.this.quit();}
	});
    private final Object shutdown = new Object();
    private boolean closing = false;
    public static String gameDir = null;
    public static boolean runningThroughSteam = true;

    static {
        try {
            if (gameDir == null) {
                if((SteamStore.steamsvc.get() != null) && (Steam.get() != null)) {
                    gameDir = System.getProperty("user.dir") + File.separator + ".." + File.separator + ".." + File.separator + "workshop" + File.separator + "content" + File.separator + "3051280" + File.separator + "3423755273" + File.separator;
                }
                else {
                    gameDir = "";
                }
            }
            FlowerMenu.createDatabaseIfNotExist();
            FlowerMenu.fillAutoChooseMap();
            HitBoxes.createDatabaseIfNotExist();
            HitBoxes.loadCollisionBoxMap();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Client(Toolkit tk) {
	this.tk = tk;
	this.wnd = tk.window();
	wnd.title("Haven & Hearth");
	Coord fsz = Utils.getprefc("mainwnd/locksize", null);
	if(fsz == null)
	    wnd.sizing(new Windeye.Sizing().minsize(new Coord(1167, 700)).normsize(Utils.getprefc("mainwnd/size", new Coord(1167, 700))));
	else
	    wnd.sizing(new Windeye.Sizing().fixsize(fsz));
	if(initfullscreen.get())
	    wnd.state(Windeye.State.EXCLUSIVE);
	else if(Utils.getprefb("mainwnd/max", false))
	    wnd.state(Windeye.State.MAXIMIZED);
	try(InputStream icon = Client.class.getResourceAsStream("icon.png")) {
	    wnd.icon(javax.imageio.ImageIO.read(icon));
	} catch(IOException e) {
	    throw(new Error(e));
	}
	wnd.add(queue);
	wnd.show(true);
    }

    public static class EventQueue implements Toolkit.EventListener {
	public final Client cl;
	private final List<Toolkit.Event> pending = new ArrayList<>();
	private Toolkit.MouseMoveEvent mousemv;
	/* Só a thread de render mexe nestes dois: são lidos e escritos dentro de
	 * dispatch(), que corre no frame. */
	private Coord lastmc = null;
	private UI lastui = null;

	public EventQueue(Client cl) {
	    this.cl = cl;
	}

	public void event(Toolkit.Event ev) {
	    if(ev instanceof Toolkit.CloseRequest) {
		Thread mt = cl.mt;
		if(mt != null)
		    mt.interrupt();
	    } else {
		synchronized(this) {
		    if(ev instanceof Toolkit.MouseMoveEvent) {
			mousemv = (Toolkit.MouseMoveEvent)ev;
		    } else {
			pending.add(ev);
		    }
		}
	    }
	}

	private static int buttonid(MouseBtn btn) {
	    if(btn == MouseBtn.Std.LEFT)
		return(1);
	    else if(btn == MouseBtn.Std.MIDDLE)
		return(2);
	    else if(btn == MouseBtn.Std.RIGHT)
		return(3);
	    else if(btn == MouseBtn.Std.BACK)
		return(4);
	    else if(btn == MouseBtn.Std.FORWARD)
		return(5);
	    return(0);
	}

	public void dispatch(UI ui) {
	    List<Toolkit.Event> evs;
	    Toolkit.MouseMoveEvent mousemv;
	    synchronized(this) {
		mousemv = this.mousemv;
		this.mousemv = null;
		evs = new ArrayList<>(pending);
		pending.clear();
	    }
	    /* Trocar de aba não gera evento de rato nenhum, mas cada UI tem o seu
	     * ui.mc -- e é dele que saem o cursor desenhado, o hover e o tooltip.
	     * A UI que entra em foco tem o mc parado onde ficou da última vez, por
	     * isso o cursor pisca para o que estava debaixo do rato na sessão
	     * anterior e só acerta quando o jogador mexe no rato. Reenviar a última
	     * posição conhecida fecha essa janela; vai sem evento de sistema para
	     * não escrever modificadores velhos por cima dos da UI nova. */
	    if((ui != lastui) && (mousemv == null) && (lastmc != null))
		ui.mousemove(lastmc);
	    lastui = ui;
	    if(mousemv != null) {
		lastmc = mousemv.wndc();
		ui.mousemove(AWTCompat.mkawt(mousemv), mousemv.wndc());
	    }
	    for(Toolkit.Event ev : evs) {
		if(ev instanceof Toolkit.MouseDownEvent) {
		    Toolkit.MouseDownEvent e = (Toolkit.MouseDownEvent)ev;
		    int btn = buttonid(e.button());
		    if(btn > 0)
			ui.mousedown(AWTCompat.mkawt(e), e.wndc(), btn);
		} else if(ev instanceof Toolkit.MouseUpEvent) {
		    Toolkit.MouseUpEvent e = (Toolkit.MouseUpEvent)ev;
		    int btn = buttonid(e.button());
		    if(btn > 0)
			ui.mouseup(AWTCompat.mkawt(e), e.wndc(), btn);
		} else if(ev instanceof Toolkit.MouseWheelEvent) {
		    Toolkit.MouseWheelEvent e = (Toolkit.MouseWheelEvent)ev;
		    if(e.axis() == Toolkit.MouseWheelEvent.Axis.VERT)
			ui.mousewheel(AWTCompat.mkawt(e), e.wndc(), e.amount(), e.subamount());
		} else if(ev instanceof Toolkit.KeyDownEvent) {
		    ui.keydown(AWTCompat.mkawt((Toolkit.KeyEvent)ev));
		    Debug.keyevent(AWTCompat.mkawt((Toolkit.KeyEvent)ev));
		} else if(ev instanceof Toolkit.KeyUpEvent) {
		    ui.keyup(AWTCompat.mkawt((Toolkit.KeyEvent)ev));
		    Debug.keyevent(AWTCompat.mkawt((Toolkit.KeyEvent)ev));
		}
	    }
	}
    }

    public static class ClientLoop extends UILoop {
	public final Client cl;

	private ClientLoop(Client cl) {
	    super(cl.wnd);
	    this.cl = cl;
	}

	public UI newui(UI.Runner fun, SessionTab tab) {
	    UI ui = super.newui(fun, tab);
	    ui.cons.add(cl);
	    return(ui);
	}

	private AudioSystem.SinkLine audiosink = null;
	protected AudioSystem.SinkLine audiosink() {
	    if(audiosink == null) {
		try {
		    audiosink = AudioSystem.instance().sinkline(Audio.defspec());
		} catch(Unavailable a) {
		    new Warning(a, "could not open an audio sink line").issue();
		    audiosink = DummyAudio.DummySink.instance;
		}
	    }
	    return(audiosink);
	}

	protected void dispatch(UI ui) {
	    cl.queue.dispatch(ui);
	}

	protected boolean bgmode() {
	    Windeye.Visibility v = wnd.visible();
	    if(v == Windeye.Visibility.UNKNOWN)
		return(!wnd.focused());
	    return(v == Windeye.Visibility.NONE);
	}

	protected void framedone(Frame f) {
	    super.framedone(f);
	    cl.titletick();
	}
    }

    private UI newui(UI.Runner fun) {
	return(loop.newui(fun));
    }

    private void quit() {
	synchronized(shutdown) {
	    closing = true;
	    shutdown.notifyAll();
	}
    }

    private volatile SessionTab curtab = null;
    private String curtitle = null;

    private void title(SessionTab tab) {
	String t = (tab == null) ? null : tab.display();
	curtitle = t;
	if((t == null) || t.equals(""))
	    wnd.title("Hurricane (" + Config.clientVersion + ")");
	else
	    wnd.title("Hurricane (" + Config.clientVersion + ") \u2013 " + t);
    }

    /* O rótulo da aba em foco muda sem ninguém avisar -- entrar no mundo troca
     * o nome da conta pelo do personagem -- e o título da janela tem de
     * acompanhar. Uma comparação de string por frame, na thread de render, sem
     * pegar em lock nenhum. */
    void titletick() {
	SessionTab tab = curtab;
	String t = (tab == null) ? null : tab.display();
	if(!Utils.eq(t, curtitle))
	    title(tab);
    }

    /* Passou a ser a aba em foco: trocar a UI desenhada, o título, e reapontar
     * os globais que só sabem de um jogador. Corre com o monitor do SessionSet
     * na mão, portanto nada aqui pode bloquear. */
    private void focus(SessionTab tab) {
	UILoop loop = this.loop;
	if(loop != null)
	    loop.focus(tab);
	curtab = tab;
	UI ui = tab.ui;
	GameUI gui = (ui == null) ? null : ui.gui;
	Config.setPlayerName((gui == null) ? null : gui.chrid);
	retarget(ui);
    }

    /* O automapper é global de um jogador só e segue o foco. Sem o campo
     * `mapped`, cada clique numa aba derrubava e recriava os quatro executores
     * do MappingClient; e sem o ramo do null, trocar para uma aba na tela de
     * login deixava o mapeamento a seguir o glob da sessão anterior. */
    private Glob mapped = null;
    private void retarget(UI ui) {
	Glob glob = ((ui == null) || (ui.sess == null)) ? null : ui.sess.glob;
	if(glob == mapped)
	    return;
	mapped = glob;
	if(glob == null)
	    haven.automated.mapper.MappingClient.destroy();
	else
	    Config.initAutomapper(ui);
    }

    private void spawn() {
	SessionTab tab = new SessionTab(sessions);
	tab.th = new HackThread(() -> tabloop(tab), "Haven session thread");
	sessions.add(tab);
	tab.th.start();
    }

    /* A cadeia de runners que antes rodava na thread principal, agora uma por
     * aba. Um runner que devolve null (logout) volta para a tela de login, como
     * sempre foi; a aba só termina quando é fechada. */
    private void tabloop(SessionTab tab) {
	boolean drop = true;
	try {
	    UI.Runner fun = null;
	    while(true) {
		if(fun == null)
		    fun = new Bootstrap();
		tab.runner = fun;
		String t = fun.title();
		tab.label((t == null) ? "login" : t);
		/* O título da janela não se escreve aqui: quem o escreve é
		 * titletick(), na thread de render, para wnd.title ter um dono
		 * só em vez de um por aba. */
		fun = fun.run(loop.newui(fun, tab));
	    }
	} catch(InterruptedException e) {
	} catch(Throwable t) {
	    /* Uma sessão que morre não derruba as outras: a aba fica na lista,
	     * marcada, até o usuário fechar. */
	    new Warning(t, "session failed").issue();
	    tab.error(String.valueOf(t.getMessage() == null ? t : t.getMessage()));
	    drop = false;
	}
	if(drop)
	    sessions.remove(tab);
    }

    private void closeall() {
	for(SessionTab tab : sessions.tabs()) {
	    tab.close();
	    Thread th = tab.th;
	    if(th != null) {
		try {
		    th.join(2000);
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		    break;
		}
	    }
	}
    }

    /* Caminho normal do cliente: abre a primeira aba e espera. O caminho de
     * sessão única (replay, -servargs) continua em run(UI.Runner). */
    public void runsessions() {
	if(mt != null) throw(new IllegalStateException());
	mt = Thread.currentThread();
	UILoop loop = this.loop = new ClientLoop(this);
	loop.sessions = sessions;
	loop.start();
	loop.startbg();
	try {
	    try {
		sessions.newtab();
		synchronized(shutdown) {
		    while(!closing)
			shutdown.wait();
		}
	    } catch(InterruptedException e) {
	    } finally {
		closeall();
		newui(null);
	    }
	    savewndstate();
	} finally {
	    loop.dispose();
	    this.loop = null;
	    mt = null;
	}
    }

    private void savewndstate() {
	switch(wnd.state()) {
	case MAXIMIZED:
	    Utils.setprefb("mainwnd/max", true);
	    break;
	case NORMAL:
	    Utils.setprefc("mainwnd/size", wnd.size());
	    Utils.setprefb("mainwnd/max", false);
	    break;
	}
    }

    public void run(UI.Runner task) {
	if(mt != null) throw(new IllegalStateException());
	mt = Thread.currentThread();
	UILoop loop = this.loop = new ClientLoop(this);
	loop.start();
	try {
	    try {
		while(task != null)
		    task = task.run(newui(task));
	    } catch(InterruptedException e) {
	    } finally {
		newui(null);
	    }
	    savewndstate();
	} finally {
	    loop.dispose();
	    this.loop = null;
	    mt = null;
	}
    }

    public void dispose() {
	wnd.dispose();
    }

    private Windeye.State prevfsstate = Windeye.State.NORMAL;
    private Map<String, Console.Command> cmdmap = new TreeMap<String, Console.Command>();
    {
	cmdmap.put("q", (cons, args) -> {
	    mt.interrupt();
	});
	cmdmap.put("fs", (cons, args) -> {
	    if(args.length >= 2) {
		Windeye wnd = Client.this.wnd;
		if(Utils.parsebool(args[1])) {
		    if(wnd.state() != Windeye.State.EXCLUSIVE) {
			prevfsstate = wnd.state();
			wnd.state(Windeye.State.EXCLUSIVE);
		    }
		} else {
		    wnd.state(prevfsstate);
		}
	    }
	});
	cmdmap.put("sz", (cons, args) -> {
	    if(args.length >= 3) {
		Coord sz = Coord.of(Integer.parseInt(args[1]),
				    Integer.parseInt(args[2]));
		Windeye wnd = Client.this.wnd;
		if((args.length >= 4) && args[3].equals("lock")) {
		    Utils.setprefc("mainwnd/locksize", sz);
		    wnd.sizing(new Windeye.Sizing().fixsize(sz));
		} else {
		    Utils.setprefc("mainwnd/locksize", null);
		    wnd.sizing(new Windeye.Sizing().minsize(new Coord(800, 600)).normsize(sz));
		}
	    }
	});
	cmdmap.put("window", (cons, args) -> {
	    if(!Client.this.tk.sharedenvs())
		throw(new Exception("Toolkit does not support multiple windows."));
	    Client cl = new Client(Client.this.tk);
	    Thread th = new HackThread(() -> {
		try {
		    cl.runsessions();
		} finally {
		    cl.dispose();
		}
	    }, "Haven alt-window thread");
	    th.start();
	});
    }
    public Map<String, Console.Command> findcmds() {
	return(cmdmap);
    }

    public static final Config.Variable<Boolean> nopreload = Config.Variable.propb("haven.nopreload", false);
    public static void setupres() {
	if(ResCache.global != null)
	    Resource.setcache(ResCache.global);
	if(Resource.resurl.get() != null)
	    Resource.addurl(Resource.resurl.get());
	if(ResCache.global != null) {
	    /*
	    try {
		Resource.loadlist(Resource.remote(), ResCache.global.fetch("tmp/allused"), -10);
	    } catch(IOException e) {}
	    */
	}
	if(!nopreload.get()) {
	    try {
		InputStream pls;
		pls = Resource.class.getResourceAsStream("res-preload");
		if(pls != null)
		    Resource.loadlist(Resource.remote(), pls, -5);
		pls = Resource.class.getResourceAsStream("res-bgload");
		if(pls != null)
		    Resource.loadlist(Resource.remote(), pls, -10);
	    } catch(IOException e) {
		throw(new Error(e));
	    }
	}
    }

    public static class ConnectionError extends RuntimeException {
	public ConnectionError(String mesg) {
	    super(mesg);
	}
    }

    public static Session connect(Object[] args) {
	Session.User acct;
	byte[] cookie;
	NamedSocketAddress gameserv = (Bootstrap.gameserv.get() != null) ?
	    Bootstrap.gameserv.get() :
	    new NamedSocketAddress(Bootstrap.authserv.get().host, Bootstrap.gameport.get());
	if((Bootstrap.authuser.get() != null) && (Bootstrap.authck.get() != null)) {
	    acct = new Session.User(Bootstrap.authuser.get());
	    cookie = Bootstrap.authck.get();
	} else {
	    String username;
	    if(Bootstrap.authuser.get() != null) {
		username = Bootstrap.authuser.get();
	    } else {
		if((username = Utils.getpref("tokenname@" + Bootstrap.authserv.get().host, null)) == null)
		    throw(new ConnectionError("no explicit or saved username for host: " + Bootstrap.authserv.get().host));
	    }
	    String token = Utils.getpref("savedtoken-" + username + "@" + Bootstrap.authserv.get().host, null);
	    if(token == null)
		throw(new ConnectionError("no saved token for user: " + username));
	    try {
		AuthClient cl = new AuthClient(Bootstrap.authserv.get());
		try {
		    try {
			acct = new AuthClient.TokenCred(username, Utils.hex.dec(token)).tryauth(cl);
		    } catch(AuthClient.Credentials.AuthException e) {
			throw(new ConnectionError("authentication with saved token failed"));
		    }
		    cookie = cl.getcookie();
		    List<NamedSocketAddress> hosts = cl.gethosts(gameserv);
		    if(!hosts.isEmpty())
			gameserv = hosts.get(0);
		} finally {
		    cl.close();
		}
	    } catch(IOException e) {
		throw(new RuntimeException(e));
	    }
	}
	try {
	    return(Session.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName(gameserv.host), gameserv.port), acct, Connection.encrypt.get(), cookie, args));
	} catch(Connection.SessionError e) {
	    throw(new ConnectionError(e.getMessage()));
	} catch(InterruptedException exc) {
	    throw(new RuntimeException(exc));
	} catch(IOException e) {
	    throw(new RuntimeException(e));
	}
    }

    private static void main2(String[] args) {
	Utils.initlocale();
	Config.cmdline(args);
	haven.error.ErrorHandler.setprop("jar.config", Config.confid);
	setupres();
	Client cl = new Client(Toolkit.instance());
	try {
	    UI.Runner main = null;
	    if(Bootstrap.replay.get() != null) {
		try {
		    Transport.Playback player = new Transport.Playback(Files.newBufferedReader(Bootstrap.replay.get(), Utils.utf8));
		    main = new RemoteUI(new Session(player, new Session.User("Playback")));
		    player.start();
		} catch(IOException e) {
		    System.err.println("hafen: " + e.getMessage());
		    System.exit(1);
		}
	    } else if(Bootstrap.servargs.get() != null) {
		try {
		    main = new RemoteUI(connect(Bootstrap.servargs.get()));
		} catch(ConnectionError e) {
		    System.err.println("hafen: " + e.getMessage());
		    System.exit(1);
		}
	    }
	    if(main != null)
		cl.run(main);
	    else
		cl.runsessions();
	} finally {
	    cl.dispose();
	}
	System.exit(0);
    }

    public static void main(String[] args) {
	/* Set up the error handler as early as humanly possible. */
	ThreadGroup g = new ThreadGroup("Haven main group");
	String ed = Utils.getprop("haven.errorurl", "");
	if(ed.equals("stderr")) {
	    g = new haven.error.SimpleHandler("Haven main group", true);
	} else if(!ed.equals("")) {
	    try {
		final haven.error.ErrorHandler hg = new haven.error.ErrorHandler(new java.net.URI(ed).toURL());
		hg.sethandler(new haven.error.ErrorGui(null) {
			public void errorsent() {
			    hg.interrupt();
			}
		    });
		g = hg;
		new DeadlockWatchdog(hg).start();
	    } catch(java.net.MalformedURLException | java.net.URISyntaxException e) {
	    }
	}
	Thread main = new HackThread(g, () -> main2(args), "Haven main thread");
	main.start();
    GobIcon.initPresets();
    AlarmManager.init();
    String runningThroughSteamValue = System.getProperty("runningThroughSteam");
    if (runningThroughSteamValue != null) {
        runningThroughSteam = Boolean.parseBoolean(runningThroughSteamValue);
    }
    }
}
