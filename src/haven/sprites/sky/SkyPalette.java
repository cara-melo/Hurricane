package haven.sprites.sky;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.sl.Type.*;

/* Game state -> sky uniforms. No GLSL maths lives here; SkyLib owns that.
 *
 * MapView pushes one of these per tick via basic(SkyPalette.class, ...).
 * equals() lets PView.basic skip rebuilding its ostate when nothing moved,
 * and since the horizon fog went there is nothing here that moves with the
 * player -- only the sun and the night lift, which Glob.ticklight
 * (Glob.java:164-183) interpolates during a two-second window after each
 * server update. Walking now costs nothing at all here. */
public class SkyPalette extends State {
    public static final Slot<SkyPalette> slot = new Slot<>(Slot.Type.DRAW, SkyPalette.class);

    /* World-space (Z-up), normalised, pointing at the sun. */
    public final float sx, sy, sz;
    /* Night Mode lift already halved -- see Glob.nightVisionBrightness. */
    public final float night;
    public SkyPalette(Coord3f sundir, double night) {
	Coord3f n = sundir.norm();
	this.sx = n.x; this.sy = n.y; this.sz = n.z;
	this.night = (float)night;
    }

    /* The sun as the world lighting sees it. Identical expression to the one
     * MapView builds for the shadow direction, so the drawn sun and the
     * shadows agree.
     *
     * Never returns null: before the server sends light data there is nothing
     * to draw a sky from, but the caller would then have to detach the state,
     * so it reports a default sun straight up instead. Nothing reads a
     * position any more -- the map rectangle went out with the horizon fog,
     * and with it the last reason for any sky code to know about MapView. */
    public static SkyPalette from(Glob glob) {
	double elev, ang;
	boolean lit;
	Astronomy ast;
	synchronized(glob) {
	    lit = (glob.lightamb != null);
	    elev = glob.lightelev;
	    ang = glob.lightang;
	    ast = glob.ast;
	}
	/* The sky debug clock, when a player has one open. It replaces BOTH
	 * halves of the vector: an elevation without its bearing would put a
	 * dusk sun in the morning sky, and SkyLib.morning gates on the bearing.
	 * MapView.amblight takes the same bearing, so the drawn sun and the
	 * shadows stay agreed at a fake hour exactly as they do at a real one.
	 *
	 * It sits above the !lit check on purpose: a player who opens the window
	 * before the server's first light blob should still get a sky, and this
	 * way everything below is untouched, so the live path is the same code
	 * it was. See ADR-0022. */
	Double fake = SkyTime.fake();
	if(fake != null)
	    return(new SkyPalette(Coord3f.o.sadd((float)sunelev(fake), (float)SkyTime.ang(fake), 1f),
				  Glob.nightVisionBrightness * NIGHT_SHARE));
	if(!lit)
	    return(new SkyPalette(new Coord3f(0f, 0f, 1f), 0.0));
	/* The azimuth still comes from the server's light, so the drawn sun and
	 * the shadows keep agreeing. That half of the vector was never wrong:
	 * aiming the camera down the shadows in game put the disc dead on that
	 * bearing, half a degree above the top edge of the screen. Only the
	 * elevation is replaced.
	 *
	 * Falling back to lightelev when ast is null is deliberate. The astro
	 * blob and the light blob arrive independently (Glob.java:267-300), so
	 * there is a window with light but no clock, and the old behaviour is a
	 * better thing to show there than a sun pinned at dawn. */
	double selev = (ast == null) ? elev : sunelev(ast.dt);
	return(new SkyPalette(Coord3f.o.sadd((float)selev, (float)ang, 1f),
			      Glob.nightVisionBrightness * NIGHT_SHARE));
    }

    /* Day fraction -> the sun's elevation for DRAWING, in radians.
     *
     * The server's lightelev cannot serve here. Measured over 4750 samples it
     * sweeps 23 to 67.5 degrees and never goes negative; through the night it
     * RISES, 0.5625 to 0.5882 rad across dt 0.808 to 0.821 with ast.night set.
     * It is the lighting rig the shadows are built from, not an astronomical
     * sun, and the client is right to keep it that way -- shadows that swing
     * to infinity at dusk would look far worse than a sun that does not set.
     *
     * Two defects followed from reading it as a sun. The disc was drawable
     * only where that sweep happened to land inside the band this projection
     * can show, which tops out at 31.75 degrees: about a third of its range,
     * and then only with the azimuth inside the 53-degree horizontal field,
     * so on the order of 5% of the time. And every night branch in SkyLib
     * keys on the elevation going negative, so the night sky, the dusk band
     * and the stars were all unreachable -- at 19:16 game time with ast.night
     * set, sky_baseA still computed a day factor of 1.583, saturated to full
     * daylight over night-lit terrain.
     *
     * PEAK compresses the arc into the drawable band with room left so the
     * disc is never clipped by the top edge. It is a compression, not a
     * physical elevation: the sky this shader draws is a fiction to begin
     * with, since a camera pitched 45 degrees down with a 15-degree half
     * field never sees real sky at all.
     *
     * Day runs dt 0.25 to 0.75, and both edges are measured. ast.night first
     * went true at dt = 0.75060 and first went false at 0.25161, with the
     * sample before it, 0.24810, still true -- so the morning edge is bracketed
     * around 0.25 to within the probe's five game-minute resolution. The
     * client's own calendar agrees independently: Cal.java:63 puts the sun on
     * the dial at (dt + 0.75) * 2pi, which is the horizontal at dt 0.25 and
     * 0.75 and the top at 0.50. */
    public static final double PEAK = 0.48;

    public static double sunelev(double dt) {
	return(PEAK * Math.sin(Math.PI * (dt - 0.25) / 0.5));
    }

    /* The azimuth the sun rises on, in radians, in the same frame the server's
     * lightang is measured in.
     *
     * lightang sweeps a full turn per game day and crosses zero near dt 0.25,
     * which is sunrise. An earlier draft carried 0.012 here, read off the fit
     * at the crossing. It does not survive its own cross-check: at dt 0.808 the
     * model predicts 2.789 rad against 2.777 measured, and that residual is
     * 0.012 -- the constant itself. Zero fits the check to 1e-4. So the fit
     * found no offset it can resolve, and the honest value is zero.
     *
     * It stays a named constant at zero rather than being folded away, because
     * what it asserts is not arithmetic: it says every world rises the sun in
     * the +x direction. That assertion should be visible to whoever finds a
     * world where it is false.
     *
     * Only SkyLib.morning reads it, to tell the morning half of the day from
     * the evening half. The sun vector itself still takes its azimuth from
     * lightang directly, in from() above, so the drawn disc and the shadows
     * keep agreeing whatever this constant says.
     *
     * If a world rises the sun somewhere else, the morning widening lands on
     * that world's evening instead. That is the hypothesis ADR-0016 puts up to
     * be falsified; the failure is ugly, not broken. */
    public static final double EAST_AZ = 0.0;

    /* What the compression above costs everything downstream.
     *
     * The drawn arc peaks at PEAK. The sun the server's own lighting describes
     * peaks at NOON -- the maximum of lightelev, measured at 1.1781 rad and
     * reached at dt 0.500 to 0.525, solar noon. (lightelev is not this sine
     * over the rest of the day; it flattens onto a floor at 0.4018. Only the
     * peak is borrowed, because only the peak is being matched.)
     *
     * So DECOMP turns a DRAWN elevation back into a real one, and any
     * PHYSICAL falloff applied to the sun has to undo the compression with it
     * first -- exactly as ADR-0005 makes anything round on screen divide by
     * sky_gain. Skipping it is what kept the twilight glow alive at -38 real
     * degrees, painting dawn over the sky at 03:37 game time. See ADR-0010.
     *
     * Angles only. A drawn elevation reaches the shader as sin(elev) inside
     * the sun vector, so undoing it means asin() first -- which is what
     * SkyLib.sunh does. */
    public static final double NOON = 1.1781;         /* rad, 67.5 degrees */
    public static final double DECOMP = NOON / PEAK;  /* 2.4544 */

    /* The sky takes half the lift the terrain takes. Full strength washes
     * the night sky to flat grey and drowns the stars. */
    public static final double NIGHT_SHARE = 0.5;

    /* The four skies the player can pick between, in the order the options
     * window lists them.
     *
     * SIMPLE and REALISTIC are not a quality ladder, whatever the option used
     * to be called. They are two different models -- SkyLib.baseA is an
     * analytic gradient, baseB is Rayleigh + Mie scattering -- and with the
     * horizon fog gone the difference between them is 0.35 ms of GPU time at
     * 1080p on integrated graphics, about 2% of a frame at 60 fps. SIMPLE is
     * the default because the client cannot detect a shader the driver will
     * not run, not because it is cheaper. */
    public static final int SIMPLE = 0, REALISTIC = 1, GALAXY = 2, CLOUDS = 3;

    /* Cached because SkyboxShader.current() would otherwise read
     * java.util.prefs on every sprite build. OptWnd calls reload() when the
     * player changes it. volatile because OptWnd writes on the UI thread while
     * the render tree reads during slot construction. */
    public static volatile int style = loadstyle();

    public static void reload() {
	style = loadstyle();
    }

    /* One pref, four values -- but the two it replaces have to be honoured
     * once, or every player who was on the procedural sky wakes up on the
     * Clouds cubemap. The old pair was skyboxStyle (0 = the procedural sky,
     * 1 = Galaxy) beside a separate skyboxQuality boolean that only meant
     * anything when skyboxStyle was 0.
     *
     * Reusing skyboxStyle for the new range is what would cause that: its 0
     * and the new SIMPLE happen to agree, but its 1 is Galaxy where the new 1
     * is REALISTIC. Hence a new key, and -1 rather than a default as the
     * "never written" marker, since 0 is a real value.
     *
     * The old keys are left where they are. They cost nothing, and a player
     * who rolls back to an earlier build finds the settings they had. */
    private static int loadstyle() {
	int v = Utils.getprefi("skyboxSky", -1);
	if(v >= 0)
	    return(v);
	int n = (Utils.getprefi("skyboxStyle", 0) == 1) ? GALAXY :
	    (Utils.getprefb("skyboxQuality", false) ? REALISTIC : SIMPLE);
	Utils.setprefi("skyboxSky", n);
	return(n);
    }

    public static final Uniform u_sundir = new Uniform(VEC3, "skysun", p -> {
	    SkyPalette s = p.get(slot);
	    return((s == null) ? new float[] {0f, 0f, 1f} : new float[] {s.sx, s.sy, s.sz});
	}, slot);
    public static final Uniform u_night = new Uniform(FLOAT, "skynight", p -> {
	    SkyPalette s = p.get(slot);
	    return((s == null) ? 0f : s.night);
	}, slot);
    /* Eye space -> world space rotation, for turning a fragment's view
     * direction back into a world direction. Same expression the old
     * skybox shader used. */
    public static final Uniform u_icam = new Uniform(MAT3, p -> Homo3D.camxf(p).transpose(), Homo3D.cam);

    /* World-space view direction of the current fragment (Z-up).
     * fragedir points at the eye, so negate it to point into the scene. */
    public static Expression viewdir(FragmentContext fctx) {
	return(Cons.mul(u_icam.ref(), Cons.neg(Homo3D.fragedir(fctx).depref())));
    }

    /* This fragment's sky elevation, in radians. SkyLib.elev owns the maths
     * and the reasoning; this only feeds it the eye-space y and z and the
     * camera's pitch.
     *
     * frageyev is the fragment's position in eye space, so atan(y, -z) is its
     * angle above the camera axis. It is an AutoVarying, which resolves on the
     * vertex context, so referencing it from inside a mod lambda is safe even
     * though the fragment value-block is locked by then. */
    public static Expression skyelev(FragmentContext fctx) {
	return(SkyLib.elev.call(Cons.pick(Homo3D.frageyev.ref(), "y"),
				Cons.pick(Homo3D.frageyev.ref(), "z"),
				u_campitch.ref()));
    }

    /* The elevation stretch in force at this pitch. Anything drawn round on
     * screen -- the sun's disc, the stars -- divides its elevation offsets by
     * it. Same uniform as skyelev, so the two can never disagree. */
    public static Expression skygain(FragmentContext fctx) {
	return(SkyLib.gain.call(u_campitch.ref()));
    }

    /* How far the camera is looking down, in radians: 0 level, pi/2 straight
     * down. FreeCam defaults to pi/4 and the drag can take it anywhere in
     * between (MapView.java:287, 328-337).
     *
     * camxf is the world-to-eye rotation, so the camera's world forward is
     * minus its third row, and m[10] -- column-major, so element (2,2) -- is
     * the negated z of that. asin of it is the pitch directly, for any
     * azimuth; verified against Camera.makepointed across four elevations and
     * three azimuths. */
    public static final Uniform u_campitch =
	new Uniform(FLOAT, "skycampitch",
		    p -> (float)Math.asin(Utils.clip(Homo3D.camxf(p).m[10], -1f, 1f)),
		    Homo3D.cam);

    public ShaderMacro shader() {return(null);}
    public void apply(Pipe p) {p.put(slot, this);}

    /* PView.basic skips rebuilding its ostate when the new palette equals the
     * old one, so a field left out of this comparison is a field that can
     * change with no effect until something else happens to move. */
    public boolean equals(Object o) {
	if(!(o instanceof SkyPalette))
	    return(false);
	SkyPalette t = (SkyPalette)o;
	return((sx == t.sx) && (sy == t.sy) && (sz == t.sz) && (night == t.night));
    }

    public int hashCode() {
	return(Float.hashCode(sx) ^ Float.hashCode(sy) ^ Float.hashCode(sz)
	       ^ Float.hashCode(night));
    }

    public String toString() {
	return(String.format("#<skypalette sun=(%f, %f, %f) night=%f>", sx, sy, sz, night));
    }

}
