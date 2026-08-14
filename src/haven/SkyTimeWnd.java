package haven;

import haven.sprites.sky.SkyPalette;
import haven.sprites.sky.SkyTime;

/* The sky's debug clock, as a window.
 *
 * It shows dt, drawn elevation and azimuth beside the wall clock because those
 * three are exactly what glprobe is invoked with -- so a screenshot taken here
 * and a render from docs/superpowers/harness/skybox/ can be taken at the same
 * instant by reading them off rather than by guessing. That is the point of the
 * window; the slider is just how you get there.
 *
 * Closing it returns the server's clock. There is no stored preference and no
 * state that outlives the widget, so a restarted client is always live. The
 * cost is real and intended: comparing two sky modes means keeping this open,
 * so it appears in every screenshot taken this way. A screenshot whose hour
 * could be a lie is not evidence, and a visible window is the cheapest way to
 * keep the two kinds of picture apart. */
public class SkyTimeWnd extends Window {
    private static final String[] SPEEDS = {"15 s", "30 s", "60 s", "2 min", "5 min"};
    private static final double[] SECS = {15.0, 30.0, 60.0, 120.0, 300.0};

    private static SkyTimeWnd cur = null;

    private final HSlider slider;
    private final Label clock, numbers;
    private final Button play, speed;
    private int spd = 1;
    private boolean playing = false;
    private String lastclock = null, lastnumbers = null;

    public SkyTimeWnd() {
	super(Coord.z, "Sky time");
	add(clock = new Label("00:00"), UI.scale(0, 0));
	add(slider = new HSlider(UI.scale(260), 0, 1439, 0) {
		public void changed() {
		    SkyTime.set(val / 1440.0);
		}
	    }, UI.scale(0, 18));
	add(numbers = new Label(""), UI.scale(0, 40));
	add(play = new Button(UI.scale(40), ">", false).action(this::toggleplay), UI.scale(0, 60));
	add(speed = new Button(UI.scale(90), "day in " + SPEEDS[spd], false).action(this::cyclespeed), UI.scale(46, 60));
	add(new Button(UI.scale(60), "live", false).action(this::golive), UI.scale(142, 60));
	add(new Label("Only the sky and the shadow bearing follow this time."), UI.scale(0, 88));
	add(new Label("The ground's light colour stays at the real hour."), UI.scale(0, 102));
	pack();
    }

    /* Mirrors TileHighlight.toggle: one window, opened and closed from the
     * options panel. */
    public static void toggle(GameUI gui) {
	if(cur != null) {
	    cur.reqclose();
	    return;
	}
	cur = gui.add(new SkyTimeWnd(), UI.scale(200, 200));
    }

    private void toggleplay() {
	playing = !playing;
	play.change(playing ? "||" : ">");
	if(playing && (SkyTime.fake() == null))
	    SkyTime.set(slider.val / 1440.0);
    }

    private void cyclespeed() {
	spd = (spd + 1) % SPEEDS.length;
	speed.change("day in " + SPEEDS[spd]);
    }

    private void golive() {
	playing = false;
	play.change(">");
	SkyTime.live();
    }

    public void tick(double dt) {
	super.tick(dt);
	/* super.tick can close this window out from under the rest of this
	 * method. ui.destroy -> Window.reqdestroy (Window.java:781) does not
	 * destroy; it sets animst = "dest" and lets the fade run, and the real
	 * destroy() is called from inside Window.tick (Window.java:696-713) on
	 * whichever frame the fade finishes. That call unlinks this window and
	 * disposes its children (Widget.java:550-553) -- so everything after
	 * this point would be running on a dead widget, and Label.settext
	 * (Label.java:73-79) would allocate a texture nothing will ever dispose.
	 * Widget.remove clears parent (Widget.java:534-540), so that is the flag. */
	if(parent == null)
	    return;
	Double fake = SkyTime.fake();
	if(playing && (fake != null)) {
	    double t = (fake + (dt / SECS[spd])) % 1.0;
	    SkyTime.set(t);
	    fake = t;
	    slider.val = (int)Math.floor(t * 1440.0);
	} else if(fake == null) {
	    /* Live: the slider follows the server so releasing the override
	     * never jumps the sky when you next grab it. */
	    Astronomy ast = null;
	    if((ui != null) && (ui.sess != null)) {
		Glob glob = ui.sess.glob;
		synchronized(glob) {
		    ast = glob.ast;
		}
	    }
	    if(ast != null)
		slider.val = (int)Math.floor(ast.dt * 1440.0);
	}
	double d = (fake != null) ? fake : (slider.val / 1440.0);
	int mins = ((int)Math.round(d * 1440.0)) % 1440;
	/* Only when it actually changed: Label.settext (Label.java:73-79)
	 * disposes and re-rasterises the Text every call, and at a fake hour
	 * held still these two strings are the same frame after frame. */
	String ct = String.format("%02d:%02d%s", mins / 60, mins % 60,
				  (fake == null) ? "   (live)" : "");
	if(!ct.equals(lastclock))
	    clock.settext(lastclock = ct);
	String nt = !Gob.skyenabled() ? "skybox disabled - the sky will not move"
	    : String.format("dt %.4f   elev %.1f deg   az %.1f deg",
			    d, Math.toDegrees(SkyPalette.sunelev(d)),
			    Math.toDegrees(SkyTime.ang(d)));
	if(!nt.equals(lastnumbers))
	    numbers.settext(lastnumbers = nt);
    }

    /* Cleared here as well as in dispose(), and the two are not redundant.
     * Closing does not destroy straight away -- Window.reqdestroy fades for
     * 0.1s first -- so without this the fake sky would outlive the click by
     * several frames, and "closing returns the server's clock" would be a
     * promise with a visible hole in it. dispose() stays as the backstop for
     * closes that never come through here, such as the UI being torn down at
     * logout. */
    public void reqclose() {
	SkyTime.live();
	ui.destroy(this);
    }

    /* dispose(), not destroy(). Widget.destroy() is remove() + rdispose()
     * (Widget.java:550-553), and rdispose() (Widget.java:528-532) recurses
     * through children calling rdispose()/dispose() -- never a child's
     * destroy(). So the bulk teardown at logout, UILoop.newui() ->
     * UI.destroy() -> root.destroy(), reaches this and would not reach a
     * destroy() override. Getting that wrong would leak the fake hour into
     * the next session and strand cur on a widget whose UI no longer ticks,
     * so the window could never be reopened. dispose() is on both paths. */
    public void dispose() {
	SkyTime.live();
	if(cur == this)
	    cur = null;
	super.dispose();
    }
}
