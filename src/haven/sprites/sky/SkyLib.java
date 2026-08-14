package haven.sprites.sky;

import haven.render.sl.*;
import static haven.render.sl.Function.PDir.*;
import static haven.render.sl.Type.*;

/* Shared GLSL for the procedural sky.
 *
 * Every function here is pure maths over its parameters -- no uniforms are
 * referenced from inside a body, so nothing here needs to know about game
 * state. SkyPalette passes the state in at the call site.
 *
 * Handedness: the two public entry points (sky_colA, sky_colB) take
 * world-space Z-up directions and convert to Y-up exactly once, at their first
 * line. Every helper below them is Y-up already and must not convert again.
 *
 * Local variables declared inside raw bodies are prefixed sk_ so they cannot
 * collide with the symbols the SL compiler generates. */
public abstract class SkyLib {
    /* A statement written as literal GLSL. "$0", "$1", ... are replaced by
     * the given expressions, which emit their own generated symbol names --
     * so parameter and uniform names are never hardcoded. */
    static Statement raw(String glsl, Expression... args) {
	return(new Statement() {
		public void walk(Walker w) {
		    for(Expression a : args)
			w.el(a);
		}

		public void output(Output out) {
		    int p = 0;
		    while(true) {
			int i = glsl.indexOf('$', p);
			if(i < 0) {
			    out.write(glsl.substring(p));
			    break;
			}
			out.write(glsl.substring(p, i));
			int j = i + 1;
			while((j < glsl.length()) && Character.isDigit(glsl.charAt(j)))
			    j++;
			args[Integer.parseInt(glsl.substring(i + 1, j))].output(out);
			p = j;
		    }
		}
	    });
    }

    /* A bare GLSL identifier, for referring to a local declared inside a raw
     * body. Only valid inside the raw block that declares that name -- there
     * is no Cons factory that can name a raw local, which is why this
     * exists. */
    static Expression id(String name) {
	return(new Expression() {
		public void walk(Walker w) {}
		public void output(Output out) {out.write(name);}
	    });
    }

    /* --- where the sky sits on the screen ---------------------------- */

    /* This game's camera never shows the horizon. FreeCam defaults to 45
     * degrees above the player (MapView.java:287) with a 15-degree vertical
     * half-field (MapView.java:124-128), so every ray on screen points 30 to
     * 60 degrees BELOW horizontal. Feeding those directions to sky maths
     * gives one flat colour, which is what the first revisions rendered.
     *
     * So the sky's elevation is driven by the ray's angle above the camera
     * axis instead of by its world elevation, stretched by GAIN and dropped
     * by TILT. Azimuth still comes from the world, so the sun stays where the
     * shadows put it and the sky turns with the camera.
     *
     * GAIN stretches the 30-degree field into roughly 90 degrees of sky, so
     * the gradient reads instead of being a slice. TILT then puts the sky's
     * own horizon just under where the loaded terrain ends -- around 30% down
     * the screen at the default zoom -- so the band the player actually sees
     * is horizon haze at the bottom and open sky above it.
     *
     * Both fade out as the camera levels off (see sky_elev): at zero pitch
     * the real horizon IS on screen, and the ray's own elevation is then the
     * right answer with no stretching at all. */
    public static final double GAIN = 3.0;
    public static final double TILT = -0.227;   /* radians, about -13 degrees */

    /* How much sky elevation one radian of screen angle buys, at this pitch.
     *
     * Anything that wants to be round ON SCREEN has to divide its elevation
     * offsets by this, because elevation reaches the screen stretched by it
     * and azimuth does not. sky_elev is the only place it may be applied. */
    public static final Function gain = new Function.Def(FLOAT, "sky_gain") {{
	Expression pitch = param(IN, FLOAT).ref();
	code.add(raw("return mix(1.0, " + GAIN + ", clamp($0 / 0.7853982, 0.0, 1.0));\n",
		     pitch));
    }};

    /* Sky elevation, in radians, for one fragment.
     *
     * ey, ez are the y and z of its eye-space position: atan(ey, -ez) is the
     * angle above the camera axis, and its iso-lines are exactly horizontal
     * screen rows. (Taking asin of the normalised direction instead bows them
     * into arcs, which renders as a curved seam across the sky.)
     *
     * pitch is how far the camera looks down, in radians. */
    public static final Function elev = new Function.Def(FLOAT, "sky_elev") {{
	Expression ey = param(IN, FLOAT).ref();
	Expression ez = param(IN, FLOAT).ref();
	Expression pitch = param(IN, FLOAT).ref();
	code.add(raw("float sk_a = atan($0, -$1);\n" +
		     "float sk_k = clamp($2 / 0.7853982, 0.0, 1.0);\n" +
		     "return (sk_a * $3) + (" + TILT + " * sk_k);\n",
		     ey, ez, pitch, gain.call(pitch)));
    }};

    /* Z-up world direction -> Y-up sky-maths direction. */
    public static final Function yup = new Function.Def(VEC3, "sky_yup") {{
	Expression v = param(IN, VEC3).ref();
	code.add(raw("return normalize(vec3($0.x, $0.z, $0.y));\n", v));
    }};

    /* The sun's REAL elevation, in radians, from the Y-up sun vector.
     *
     * The vector's own y is the sine of the DRAWN elevation, and the drawn
     * elevation is compressed to fit the band of ADR-0004 -- so it is a
     * fabricated quantity and no physical falloff may be applied to it
     * directly (SkyPalette.DECOMP). Everything below that models an
     * atmosphere -- twilight, the day blend, star visibility, the cloud tint
     * -- goes through this first.
     *
     * The coefficients those places carry were written against sun.y and are
     * kept as they stand. They now read per radian of real elevation instead
     * of per unit of compressed sine, which leaves each one unchanged at the
     * horizon, where the look was judged, and only alters how fast it lets go
     * away from it. That is the whole of the fix. */
    public static final Function sunh = new Function.Def(FLOAT, "sky_sunh") {{
	Expression s = param(IN, VEC3).ref();
	code.add(raw("return asin(clamp($0.y, -1.0, 1.0)) * " + SkyPalette.DECOMP + ";\n", s));
    }};

    /* The twilight boundaries, as astronomy defines them: the sun's depression
     * below the horizon at which each stage ends. They are used as the scale
     * of the falloffs below, so those falloffs land on the real clock. */
    public static final double CIVIL = 0.10472;   /* rad, 6 degrees */
    public static final double ASTRO = 0.31416;   /* rad, 18 degrees */

    /* The scale the day-to-night blends run on, and it is CIVIL rather than
     * ASTRO because this game's night is not astronomy's.
     *
     * The client's own calendar swaps its day sky for its night sky in a single
     * frame (Cal.java:58), keyed on ast.night, which ADR-0006 measured going
     * true at dt 0.75060 -- 18:01, one game minute after the dial puts the sun
     * on the horizon (Cal.java:63). The terrain's lighting steps with it: over
     * the 28 game minutes from 17:33 to 18:02, measured in the client, the
     * ground fell 56% and this sky fell 12%.
     *
     * On the ASTRO scale sky_sunh reaches -18 degrees at dt 0.79284, so the sky
     * finished its night at 19:02 -- 61 game minutes after the calendar said
     * night. CIVIL puts it at 18:20, which is 19. Rendered at 18, 6 and 3
     * degrees and chosen from those: 3 degrees darkens the sky faster than the
     * clouds' own day factor can follow, leaving white cloud over a black sky.
     *
     * Astronomy still owns TWI, which is a glow's falloff and not a clock. */
    public static final double NIGHT_EDGE = CIVIL;

    /* Twilight brightness against the sun's depression. Set so the glow is 1%
     * of its horizon value at the astronomical end: exp(-ASTRO * TWI) = 0.01.
     *
     * The old form was exp(-abs(sun.y) * 7.0) on the compressed sine, which
     * never fell below 0.15 through the whole night and still stood at 0.04 at
     * noon, because that sine cannot exceed sin(PEAK) = 0.46. */
    public static final double TWI = 14.66;       /* per radian of depression */

    /* Cloud tops keep the sun after the ground has lost it, so their tint
     * outlives the sky's band -- 37% at civil twilight against the sky's 21%.
     * Wider on purpose; this is the one constant here chosen by eye. */
    public static final double TWI_CLOUD = 9.5;

    /* The morning half of mode B's night, and only the morning half.
     *
     * sk_nit is scaled by 1 - exp(sh), which GROWS with the sun's depression:
     * 0.6921 at midnight, 0.19 at 05:20. So the night was three times brighter
     * in the small hours than in the hour before dawn, and the sky bottomed out
     * 34 game minutes BEFORE sunrise -- 05:26, found by a five-minute scan;
     * three files here once said 05:20 -- while the client's own calendar had
     * already been drawing a sunrise since 04:05. Measured whole-frame mean:
     * 0.2150 at 00:00, 0.1733 at 04:51, 0.1617 at 05:20, 0.3832 at 06:00.
     *
     * NIGHT_RATE steepens that curve so it has done most of its work early,
     * and NIGHT_SCALE is then forced rather than chosen: it is whatever makes
     * midnight come out where the shipped sky puts it, so the gate's other
     * crossing is a crossfade between two equal values. That division has to be
     * redone whenever NIGHT_RATE moves. At midnight sh is -1.1781, so the
     * morning curve stands at 1 - exp(5 * -1.1781) = 0.99724, and the shipped
     * 0.038 * 0.6921 = 0.026301 divided by it is 0.026374.
     *
     * NIGHT_RATE is 5.0, and the two values it is not are both instructive.
     *
     * 12.0 shipped first. 1 - exp(sh * RATE) does not remove the inversion, it
     * COMPRESSES it: a steeper curve saturates sooner and then still has to
     * fall to zero at the horizon. At 12.0 it was still at 46% of its midnight
     * value with the sun 2.9 degrees down, where the shipped curve was at 7%
     * -- so the last ten minutes before sunrise got a blue wash laid over
     * exactly the moment the sky had started to warm, and the ground haze went
     * from brown to grey. Whole-frame median red minus blue at 05:50, in
     * levels: shipped +20.6, at 12.0 +10.1, at 5.0 +19.6.
     *
     * 4.0 replaced it, and was chosen from a sweep that only ever pointed the
     * camera AT the sun. Turn 180 degrees and the trade reverses, monotonically
     * across the whole family -- night minimum as a fraction of that shader's
     * own midnight, facing the sun and facing away:
     *
     *      shipped  -23.7%  -41.1%      5.0   +3.1%  -21.8%
     *          4.0   +2.8%  -25.5%     12.0   +3.2%   -6.4%
     *
     * Those away figures are the 180-degree bearing, which is the FLATTERING
     * one. Over the whole rose the accepted shader reads -17.9% at 90 degrees,
     * -30.8% at 135, -21.8% at 180, -18.6% at 225 and -26.3% at 270: the worst
     * of the far side is broadside, not opposite. See ADR-0016.
     *
     * No value fixes both, because the structure will not allow it: away from
     * the sun the sky is held up by sk_nit alone, which must reach zero at the
     * horizon, while the dawn's warmth carries pow(mu, 5) and is welded to the
     * sun's own bearing. Curing the far side needs a dawn term that is not
     * weighted by mu -- a different shape, not a different constant. 5.0 buys
     * four points of it for about one level of morning hue, and is a stopping
     * place, not a solution. See ADR-0016.
     *
     * The 12.0 defect hid for a whole review, and not because the instrument
     * was blind. Ablating the two terms at 05:50 gives a median red minus blue
     * of -50.9 for the night alone and -16.0 for the glow alone against -35.8
     * for both, so the median sees them ADDITIVELY and would have shown the
     * cooling. It did show it: 05:50 was in the table, at +20.6 falling to
     * +10.1, under a sentence saying the hue barely moved. The failure was
     * prose overruling a row of its own table. Read the rows.
     *
     * TWI_DAWN is the morning's own twilight falloff. TWI is set so the glow is
     * 1% of its horizon value at 18 degrees of depression -- exp(-14.66*0.3142)
     * = 0.0100 -- and at 3.50 it is still 33% there, exp(-3.50*0.3142) = 0.333.
     * An earlier draft said 44%, which is the figure for a rate of 2.61 and was
     * left behind by a change to the constant. That is what puts a warm side on
     * the sky an hour before the
     * disc arrives. Its 0.10 coefficient is not a choice either: both sides of
     * the mix reduce to 0.10 at sh = 0, which is what keeps sunrise itself
     * bit-identical. Warmth added by raising it would move 06:00. */
    public static final double NIGHT_RATE = 5.0;
    public static final double NIGHT_SCALE = 0.026374; /* 0.038 * 0.6921 / 0.99724 */
    public static final double TWI_DAWN = 3.50;        /* per radian, mornings only */

    /* --- Mode B cloud constants (ADR-0013) ---------------------------------
     *
     * sky_cloudsB builds a BILLOW field -- abs(2n-1) per octave -- which is not
     * the field ADR-0008 measured, so none of that ADR's numbers carry over to
     * it. Measured over 400k samples at four octaves:
     *
     *     mean 0.3351   sd 0.1370   min 0.0117   max 0.8464
     *     p70  0.4063   p90 0.5251
     *
     * The coverage window is half a standard deviation centred on p70, the same
     * placement rule ADR-0008 settled on. CLOUD_CORE is the field's own p90, so
     * a tenth of the field exceeds it by construction -- reachable, not clipped.
     *
     * Anyone changing the octave count, the basis or the lacunarity must
     * re-measure and re-place all three, because a different field has a
     * different distribution and these numbers will mean something else.
     * `python3 measure.py dist` in the ADR-0009 harness prints them. */
    public static final double CLOUD_ON = 0.372;      /* p70 - sd/4 */
    public static final double CLOUD_FULL = 0.441;    /* p70 + sd/4 */
    public static final double CLOUD_CORE = 0.5251;   /* the field's p90 */

    /* Frequency, lowered from the 4.5 sky_clouds uses, for larger masses.
     *
     * The drift constants below are NOT the ones in sky_clouds, and that is
     * forced rather than chosen. ADR-0008 derived 0.0050 and 0.0020 cells per
     * game-second from "the visible band is about 4.3 cells wide" AT FREQUENCY
     * 4.5, giving 243 s to cross. A cell is frequency-independent, but the
     * number of cells spanning the fixed on-screen band is not: at 3.8 the band
     * is 4.3 * 3.8/4.5 = 3.6 cells, so the same drift would cross it in 205 s --
     * 16% faster than the speed that was measured and judged. Scaling both by
     * 3.8/4.5 restores 243 s. */
    public static final double CLOUD_FREQ = 3.8;
    public static final double CLOUD_DRIFT_X = 0.00422;   /* 0.0050 * 3.8/4.5 */
    public static final double CLOUD_DRIFT_Y = 0.00169;   /* 0.0020 * 3.8/4.5 */

    /* How far toward the sun the second sample is taken, in noise cells. This is
     * the whole of the shading that follows the sun, so it is measured on the
     * term itself -- sd of sk_dir inside the cloud, and how far sk_dir moves when
     * only the sun's azimuth does -- and not on the frame's contrast, which
     * cannot tell shading that follows the sun from shading that follows
     * thickness. The sun response peaks here and falls off both ways: 0.29 at
     * 0.30 cells against 0.27 at 0.20, 0.22 at 0.60 and 0.14 at 1.40. Below about
     * 0.2 the two samples merge and the directional term goes flat at 0.5; above
     * about 0.9 the shadow detaches from the cloud casting it and smears into
     * clear sky, which the contrast figure rewards and the eye does not.
     *
     * It is a CONSTANT and not a function of the sun's elevation, which is not
     * what this was meant to be. Shadow length goes as 1/tan(elevation), so the
     * reach was to fall off with the sun's real elevation per ADR-0010 and
     * ADR-0011. Two things killed that. The term reads normalize(s.xz), a unit
     * azimuth direction, so the sun's height has no path into it at all -- the
     * sweep returns byte-identical rows at three elevations. And centring
     * 1/tan on this value demands 0.106 cells at high sun and 1.062 at low, a
     * tenfold swing across a usable band only twofold wide, so the law would
     * degrade every elevation but the one it was centred on. Measured, not
     * assumed; ADR-0013 carries the numbers. */
    public static final double CLOUD_REACH = 0.30;

    /* Octaves of the sun-ward sample. The pixel's own sample runs the full four;
     * this one stops early, and above the cut both accumulators are fed the same
     * value, so those octaves cancel exactly out of sk_ps - sk_p. Nothing is
     * biased -- only high-frequency detail is dropped from a shading gradient
     * that is low-frequency by design.
     *
     * It is here because the second sample doubles the noise work and that broke
     * the frame budget. On the integrated GPU at 1080p the cloud layer costs
     * 1.74 ms with four octaves against the shipped shader's 1.07, and the gate
     * is that this change adds under 0.6 ms to what the clouds already cost.
     * Four adds 0.67 and fails; three adds 0.50; two adds 0.30. Two and three
     * measure identically on internal contrast and sun response, two costs one
     * level of 255 on the away-from-the-sun median, and all three are
     * indistinguishable rendered side by side -- so the budget decides. */
    public static final int CLOUD_SUN_OCT = 2;

    /* --- shared output transform ------------------------------------- */

    /* Reinhard + gamma. Both modes MUST pass through this and only this, so
     * they stay in one colour space. It had a second reason to be shared --
     * the horizon fog fading the map edge into the sky, which drifted visibly
     * if it tonemapped separately -- and that consumer is gone.
     *
     * Reinhard runs on the LUMINANCE and the chroma ratios are carried through
     * it, rather than each channel being compressed on its own. Per channel it
     * is the brightest channel that gets compressed hardest, so a compression
     * is also a desaturation: with the white point at 0.72 and this model
     * arriving above it, the daylight band came out at a saturation of 0.05 to
     * 0.11 -- the grey ADR-0021 is about. On the luminance the ratios survive
     * the compression, and the same band reads 0.12 to 0.27.
     *
     * min() against white is the gamut clamp, and it is where the hue can
     * still shift: a ratio scaled to a luminance the cube cannot hold has to
     * lose the channel that leaves first. That only bites on the sun disc and
     * the brightest cloud tops, which are white already.
     *
     * This is shared, so it repaints every hour and not only the day, which is
     * the cost ADR-0021 weighs: it is what moves the two twilights by 22
     * levels of 255 and the night by 7, and what took dawn_check's HOLD family
     * off ADR-0016's baseline and onto this one. */
    public static final Function tone = new Function.Def(VEC3, "sky_tone") {{
	Expression c = param(IN, VEC3).ref();
	code.add(raw("float sk_L = dot($0, vec3(0.2126, 0.7152, 0.0722));\n" +
		     "float sk_T = sk_L / (sk_L + 0.72);\n" +
		     "vec3 sk_c = min($0 * (sk_T / max(sk_L, 1.0e-5)), vec3(1.0));\n" +
		     "return pow(sk_c, vec3(1.0 / 2.2));\n", c));
    }};

    /* --- shared sky features (Y-up) ---------------------------------- */

    /* The sun.
     *
     * The obvious dot(d, s) measures the angle between two directions in the
     * SKY, and the sky is not what the player sees: elevation arrives on
     * screen divided by sky_gain. A disc that is round in sky angle therefore
     * lands as an ellipse three times wider than tall at full pitch, which is
     * what the earlier revisions drew -- 44 px across and 14 px high.
     *
     * So the offset is measured the way the screen measures it. Two scales,
     * both verified against the client's own ray construction across five
     * pitches (agreement to 1.6%, the residual being the perspective term):
     *
     *   pixels per radian of azimuth   = S * cos(phi)
     *   pixels per radian of elevation = S / gain
     *
     * where phi is the ray's TRUE world elevation, not the fabricated sky one.
     * The azimuth scale foreshortens because a ray pointing steeply down sweeps
     * more azimuth per pixel; at the default 45 degrees of pitch the two scales
     * differ by 2.43, not by the gain's 3.0, and the difference is entirely
     * that cosine. Weighting azimuth by cos(phi) also keeps the disc a fixed
     * size in pixels rather than growing as the camera tips.
     *
     * At a level camera gain is 1, the sky elevation IS the ray elevation, and
     * this reduces exactly to the angle between the two directions -- which is
     * what dot(d, s) was. Along the horizontal it also stays the old function
     * to second order, since pow(cos(a), 3000) is exp(-1500 a^2), so the width
     * and the tuning that produced it carry over untouched. */
    public static final double DISC = 1500.0;   /* half brightness at 1.23 degrees */

    /* How far the sun sinks before the disc is gone, in real radians. The sun
     * is about half a degree across, so a body that sets crosses its own
     * diameter in roughly that much depression; 1.5 degrees keeps the last of
     * it from popping out on a frame boundary. */
    public static final double SET = 0.0262;    /* rad, 1.5 degrees */

    public static final Function disc = new Function.Def(VEC3, "sky_disc") {{
	Expression d = param(IN, VEC3).ref();
	Expression s = param(IN, VEC3).ref();
	Expression g = param(IN, FLOAT).ref();
	Expression ch = param(IN, FLOAT).ref();
	Expression sh = param(IN, FLOAT).ref();
	/* Nothing else hides a set sun. The projection of ADR-0004 shows only
	 * a band 30 to 60 degrees BELOW horizontal, so there is no terrain
	 * edge and no sky horizon in frame to occlude it: without this gate the
	 * disc is drawn all night, wherever the compressed elevation puts it.
	 * Rendered offline at dt 0.1535 -- 03:37 game time, the sun 38 real
	 * degrees under -- and the disc was plainly on screen. */
	code.add(raw("float sk_up = clamp($4 / " + SET + " + 1.0, 0.0, 1.0);\n" +
		     "if(sk_up <= 0.0) return vec3(0.0);\n" +
		     "float sk_da = atan($0.z, $0.x) - atan($1.z, $1.x);\n" +
		     "sk_da = (mod(sk_da + 3.14159265, 6.2831853) - 3.14159265) * $3;\n" +
		     "float sk_de = (asin(clamp($0.y, -1.0, 1.0))\n" +
		     "               - asin(clamp($1.y, -1.0, 1.0))) / $2;\n" +
		     "return vec3(1.0, 0.96, 0.86)\n" +
		     "       * exp(-(sk_da * sk_da + sk_de * sk_de) * " + DISC + ") * 6.0 * sk_up;\n",
		     d, s, g, ch, sh));
    }};

    /* Stars.
     *
     * The first version quantised a planar projection of the ray and lit one
     * whole cell per star. Measured on a night capture, that put each star on
     * screen as a 2-3 px wide, 1 px TALL dash -- the cell there was 5.3 px by
     * 0.86 px, because elevation reaches the screen divided by GAIN while
     * azimuth does not. It also laid them on a lattice at 1.7 stars per square
     * degree, eight times the naked-eye sky, all at one apparent brightness;
     * and it drew the twinkle phase from the same hash that decided whether a
     * cell held a star at all. Since only hashes above 0.9965 became stars, the
     * whole field shared 18 degrees of phase and pulsed in unison -- a 0.9 Hz
     * beat, plainly visible in the capture's spectrum.
     *
     * So: cells square on SCREEN (elevation divided by GAIN before gridding),
     * one jittered round point inside each, and a magnitude drawn from the real
     * count law. N(<m) goes as 10^(0.55 m) and brightness as 10^(-0.4 m), so
     * inverting the CDF gives L = r^-0.727: a handful of bright stars and a
     * great many faint ones, spanning 250 to 1 before the tonemap. Twinkle
     * phase comes from an independent hash channel, and its depth rises toward
     * the horizon, where the air is thickest. */
    public static final double STAR_NCOL = 1420.0;  /* cells around the horizon */
    public static final double STAR_CELL = 226.0;   /* cells per radian of azimuth */
    public static final double STAR_OCC = 0.10;     /* fraction of cells holding a star */
    public static final double STAR_FAINT = 0.020;  /* radiance of the dimmest star */

    public static final Function stars = new Function.Def(VEC3, "sky_stars") {{
	Expression d = param(IN, VEC3).ref();
	Expression s = param(IN, VEC3).ref();
	Expression t = param(IN, FLOAT).ref();
	Expression g = param(IN, FLOAT).ref();
	Expression sh = param(IN, FLOAT).ref();
	/* 2*pi*226 is 1419.9999, an integer to one part in ten million, so
	 * wrapping the column index closes the ring at azimuth +-pi with no
	 * seam and no runt cell. */
	/* Full strength on NIGHT_EDGE, with the sky's own blend, so the stars
	 * arrive as the sky that was hiding them goes out. Left on ASTRO they
	 * reached full at 19:02 over a sky that had been dark since 18:20 --
	 * legible, but forty minutes of a night still filling in after the
	 * calendar had called it. The old gate on the compressed sine was worse
	 * again: full only at 47 real degrees of depression. */
	code.add(raw("float sk_night = clamp(-$4 / " + NIGHT_EDGE + ", 0.0, 1.0);\n" +
		     "float sk_hz = clamp($0.y * 3.0, 0.0, 1.0);\n" +
		     "if(sk_night <= 0.001 || sk_hz <= 0.0) return vec3(0.0);\n" +
		     "vec2 sk_g = vec2((atan($0.z, $0.x) + 3.14159265) * " + STAR_CELL + ",\n" +
		     "                 asin(clamp($0.y, -1.0, 1.0)) * (" + STAR_CELL + " / $3));\n" +
		     "vec2 sk_c = floor(sk_g), sk_f = sk_g - sk_c;\n" +
		     "sk_c.x = mod(sk_c.x, " + STAR_NCOL + ");\n" +
		     /* Hoskins hash42: four decorrelated values without sin(),
		      * whose precision would fray at these cell indices. */
		     "vec4 sk_p4 = fract(sk_c.xyxy * vec4(0.1031, 0.1030, 0.0973, 0.1099));\n" +
		     "sk_p4 += dot(sk_p4, sk_p4.wzxy + 33.33);\n" +
		     "vec4 sk_h = fract((sk_p4.xxyz + sk_p4.yzzw) * sk_p4.zywx);\n" +
		     "float sk_r = (sk_h.x - (1.0 - " + STAR_OCC + ")) / " + STAR_OCC + ";\n" +
		     "if(sk_r <= 0.0) return vec3(0.0);\n" +
		     "float sk_l = " + STAR_FAINT + " * pow(max(sk_r, 5.0e-4), -0.727);\n" +
		     "vec2 sk_o = sk_f - (0.2 + 0.6 * sk_h.yz);\n" +
		     "float sk_d2 = dot(sk_o, sk_o);\n" +
		     "float sk_v = sk_l * (exp(-sk_d2 * 60.0) + 0.22 * exp(-sk_d2 * 14.0));\n" +
		     "sk_v *= 1.0 + 0.22 * (1.0 - clamp($0.y, 0.0, 1.0))\n" +
		     "             * sin($2 * 1.2 + sk_h.w * 6.2831853);\n" +
		     "vec3 sk_tint = mix(vec3(0.80, 0.87, 1.00), vec3(1.00, 0.89, 0.78),\n" +
		     "                   fract(sk_h.w * 13.0));\n" +
		     "return sk_tint * (sk_v * sk_night * sk_hz);\n", d, s, t, g, sh));
    }};

    /* --- Mode A: analytic gradient (Y-up, no sun disc) ---------------- */

    public static final Function baseA = new Function.Def(VEC3, "sky_baseA") {{
	Expression d = param(IN, VEC3).ref();
	Expression s = param(IN, VEC3).ref();
	/* The sun's real elevation, from sky_sunh. Passed rather than computed
	 * here so the entry point pays for the asin once. */
	Expression sh = param(IN, FLOAT).ref();
	/* The day zenith is deeper, and the exponent higher, than the
	 * prototype's. The prototype was judged on a full sky dome; here only
	 * the 0-to-30-degree band above the terrain is ever on screen, and at
	 * 0.42 that band was almost entirely the pale horizon end of the
	 * gradient -- measured 12 points of separation across the whole
	 * visible sky. 0.75 moves the pale part down into the strip just above
	 * the terrain and leaves open blue above it. (That strip used to be
	 * the one the horizon fog covered, which is what the value was chosen
	 * against; the fog is gone, the framing is not.) */
	code.add(raw("float sk_sh = $2;\n" +
		     /* Above the horizon the old line stands. Below it, twilight
		      * does not stop at a point -- it decays -- so the linear
		      * branch, which hit zero at 5.7 degrees of depression and
		      * left civil twilight rendering as full night, continues as
		      * the exponential that matches it in both value and slope
		      * there: 0.25 * exp(10 * sh), since 0.25 * 10 = 2.5. The
		      * rate is the linear branch's own, not a chosen one.
		      *
		      * Measured over the dawn: this lifts 05:40 from 0.350 to
		      * 0.421 mean and halves the 05:40-to-05:50 step, while
		      * 06:00, 12:00 and 18:00 come out bit-identical. */
		     "float sk_day = (sk_sh >= 0.0) ? clamp(sk_sh * 2.5 + 0.25, 0.0, 1.0)\n" +
		     "                              : (0.25 * exp(sk_sh * 10.0));\n" +
		     "vec3 sk_zen = mix(vec3(0.015, 0.020, 0.055), vec3(0.085, 0.27, 0.72), sk_day);\n" +
		     "vec3 sk_hor = mix(vec3(0.045, 0.050, 0.090), vec3(0.70, 0.82, 0.95), sk_day);\n" +
		     "float sk_t = pow(clamp($0.y, 0.0, 1.0), 0.75);\n" +
		     "vec3 sk_col = mix(sk_hor, sk_zen, sk_t);\n" +
		     "float sk_sd = max(dot($0, $1), 0.0);\n" +
		     "float sk_dusk = exp(-abs(sk_sh) * " + TWI + ");\n" +
		     "sk_col += vec3(1.0, 0.42, 0.13) * pow(sk_sd, 5.0) * (1.0 - clamp($0.y, 0.0, 1.0)) * sk_dusk * 1.1;\n" +
		     /* The tight halo is direct sunlight scattered forward, so it
		      * ends when the sun does rather than lingering to the 21 real
		      * degrees of depression the old gate on sun.y allowed. */
		     "sk_col += vec3(1.0, 0.72, 0.35) * pow(sk_sd, 40.0)\n" +
		     "          * clamp((sk_sh + " + CIVIL + ") / " + CIVIL + ", 0.0, 1.0) * 0.8;\n" +
		     /* Below the horizon the gradient term above is clamped
		      * flat, and that is the only region this game's camera
		      * ever shows: FreeCam sits at 45 degrees with a 30-degree
		      * vertical field, so the screen spans 30 to 60 degrees
		      * BELOW horizontal and the horizon is never in frame.
		      * Continue into a ground haze so the visible band has
		      * depth instead of being one flat colour. */
		     "sk_col = mix(sk_col, sk_hor * vec3(0.55, 0.54, 0.52),\n" +
		     "             pow(clamp(-$0.y, 0.0, 1.0), 0.7));\n" +
		     "return sk_col;\n", d, s, sh));
    }};

    /* 1 in the morning, 0 in the afternoon.
     *
     * Every twilight term in baseB reads the sun's ELEVATION, and elevation is
     * a sine about noon -- 04:51 and 19:08 arrive at the shader as the same
     * number to 8e-17, and the sky they produce is bit-identical. So elevation
     * cannot tell dawn from dusk, and anything keyed on it moves both. The
     * sun's horizontal direction can: it is 180 degrees apart at the two ends
     * of the day.
     *
     * The parameter is the Y-UP sun, the one sky_yup produced -- yup maps world
     * (x, y, z) to (x, z, y), so this vector's .xz IS the world's horizontal
     * plane, which is the plane lightang is measured in. Handing it the
     * world-space sun would put the sun's height into .z and skew the gate
     * silently.
     *
     * The 0.25 half-width is in dot space, not in azimuth: it puts the
     * crossfade between 75.5 and 104.5 degrees off east, which the sun takes
     * about an hour and 56 minutes to cross. That is the parameter that decides
     * how long each turn takes, and it was not derived -- it was chosen to be
     * slow enough that neither crossing can read as an edge, and both crossings
     * sit in hours nobody was tuning. Narrowing it sharpens both fences.
     *
     * Any gate that halves a circle has two boundaries. This one turns where
     * the sun is due north or south -- midnight and noon -- and at both the dot
     * is 0, so this returns exactly 0.5. Neither is a decision; both are the
     * fence. What makes that cheap differs per term:
     *
     *   sk_nit is genuinely inert at noon -- the day/night blend gives it zero
     *   weight with the sun up -- and at midnight NIGHT_SCALE makes the two
     *   sides of its mix equal, so half of either is the same number.
     *
     *   The glow is NOT inert, only small. Its only gate is exp(-abs(sh) * k)
     *   and k is what this swaps: at noon 3.2e-09 against 1.6e-03, so 8.1e-04
     *   survives at 0.5. That is 2.7e-05 on the darkest pixel after the
     *   tonemap, and most of the 2.4e-03 measured at midnight.
     *
     * Both are under a level of 255 and both are accepted. They scale with
     * TWI_DAWN, though: at 1.00 instead of 3.50 the noon leak is nineteen times
     * larger and would stop being invisible. The crossings are cheap at this
     * width, not free at every width.
     *
     * The zero-length guard is here for the plain reason: normalize(vec2(0))
     * is NaN and would poison the whole sky. Unreachable with a real sun, which
     * cannot exceed PEAK of elevation, but the vector is a uniform. */
    public static final Function morning = new Function.Def(FLOAT, "sky_morning") {{
	Expression s = param(IN, VEC3).ref();
	code.add(raw("vec2 sk_az = vec2($0.x, $0.z);\n" +
		     "float sk_al = length(sk_az);\n" +
		     "sk_az = (sk_al < 1.0e-5) ? vec2(1.0, 0.0) : (sk_az / sk_al);\n" +
		     "return smoothstep(-0.25, 0.25, dot(sk_az, vec2(" +
		     (float)Math.cos(SkyPalette.EAST_AZ) + ", " +
		     (float)Math.sin(SkyPalette.EAST_AZ) + ")));\n", s));
    }};

    /* Mode B's daylight exposure, and the elevation over which it opens.
     *
     * sky_tone is Reinhard with a white point of 0.72, and this model arrives
     * at it far above that. Linear radiance of the band the camera actually
     * shows, at 08:45: 1.92 facing away from the sun and 2.55 facing it, with
     * 100% of those pixels past the knee. Mode A arrives at 0.57 and 0.81, so
     * it is under the knee and mode B never is.
     *
     * What that costs is COLOUR, not just brightness, because Reinhard presses
     * the channels together as it compresses. The linear sky here is not grey
     * -- normalised it reads (0.550, 0.775, 1.000) against mode A's (0.513,
     * 0.686, 1.000), so it is the better blue of the two -- and it reached the
     * screen at (209, 219, 226), a saturation of 0.078. Sampled off a player's
     * screenshot at the same hour: (206, 214, 218), 0.068.
     *
     * The defect that decides the value is not the brightness, which is a
     * taste, but the FLATNESS, which is not. Measured across the visible band
     * from 07:00 to 17:00 the whole daylight arc spans 8.7 levels of 255:
     * 08:45, noon and 15:00 are within three levels of each other, because
     * above the knee a difference in radiance is not a difference on screen.
     * At 0.25 that arc is 29.9 levels.
     *
     * Both of those are the 180-degree bearing, and neither is the whole
     * picture -- see DAY_EXPO_UP for why one bearing is not a measurement here.
     * Swept over eight, the arc runs 7.8 to 17.1 before and 12.3 to 39.4 after.
     * Every bearing gains and the worst one is still nearly flat, which this
     * constant cannot fix: the gate multiplies sk_day, so it scales whatever
     * elevation response a bearing already has, and where competing terms
     * cancel there is nothing to scale. dawn_check's DAY family therefore
     * asserts that no bearing loses arc and prints the floor it does not reach.
     *
     * Two candidate justifications were measured and both failed, and they are
     * recorded so they are not proposed again. Matching mode A's reading is
     * copying another shader's fingerprint, which this directory's own checks
     * condemn. And sitting the band ON the white point does not exist: swept
     * over six hours and three bearings, 0.30 puts it between 0.73 and 1.71,
     * so no constant lands the whole day under 0.72 without a dark noon. The
     * survival curve has no knee to stop at either -- Reinhard is a smooth
     * hyperbola, and each step down buys about the same colour back. 0.25 is a
     * judgement made from renders, bounded by the arc figure above.
     *
     * DAY_EXPO_UP is what keeps the change out of everything that was tuned.
     * The gate is clamped at 1.0 for a sun at or below the horizon, so every
     * twilight and the whole night are untouched -- verified as an exact
     * equality, not a bound: over thirteen times from 18:00 through 05:55 and
     * four camera bearings, the maximum per-pixel difference is 0.000e+00. A
     * FLAT exposure is what that rules out; at 0.25 it takes 05:20 to 0.199
     * against dawn_check's floor of 0.220 and moves 06:00 by 48 levels where
     * the contract is bit-identity.
     *
     * DAY_EXPO_UP is 0.25 and was 0.5, and the correction is worth recording
     * because of HOW the first value survived review. Everything that chose it
     * was measured at 08:45 and at one camera bearing, 180 degrees off the sun
     * -- which ADR-0016 already names as the FLATTERING bearing, in this same
     * file's neighbourhood. Swept over nine hours and eight bearings instead,
     * 08:45 reads 0.64 there and 1.04 to 1.30 at four other bearings, so the
     * worst case was double what was judged.
     *
     * The same sweep found the real defect in 0.5: it moved the peak rather
     * than removing it. Before, the worst cell was noon at 2.91; at 0.5 it
     * became 17:00 at 1.67, because a gate that must equal 1.0 at the horizon
     * gives the shoulder hours almost nothing -- 17:00 got 0.54, not 0.25, and
     * came out at a saturation of 0.051, worse than the 0.073 that started the
     * complaint. Nothing regressed, every cell improved, but the correction was
     * uneven enough to leave a new worst hour nobody had looked at.
     *
     * 0.25 is a strict improvement over 0.5, which is why it needs no taste
     * argument: 08:45 and 12:00 come out bit-identical, and the shoulders gain
     * -- 17:00 to 0.063, 07:00 from 0.062 to 0.082. Across the grid the worst
     * cell falls 1.67 to 1.54 and the share still past the knee 93% to 82%.
     *
     * The obvious objection is that a faster ramp becomes a visible transition,
     * and it is empirically false. Stepped every ten game minutes across
     * sunrise, the sky's own natural motion is 22.8 levels of 255 per step,
     * while this gate's largest step is 16.9 at 0.25 and 14.2 even at 0.18. The
     * gate cannot produce a change the sunrise is not already making.
     *
     * 0.25 radians of real elevation is reached at 06:52 and by symmetry at
     * 17:08, so the gate is fully open across the hours anyone plays, tapers
     * only in the forty minutes at each end, and is shut across the hours
     * anyone tuned. 0.18 was measured too and buys nothing further: the worst
     * cell does not move at all.
     *
     * One thing the sweep turned up and this does not explain: the daylight
     * arc is not shaped like a day. Its darkest point is 08:45 and its
     * brightest 17:00, with noon between them. Whatever that is, it is not
     * this constant -- the ordering is the same before and after. */
    public static final double DAY_EXPO = 0.25;
    public static final double DAY_EXPO_UP = 0.25;  /* rad of real elevation */

    /* --- Mode B: Rayleigh + Mie (Y-up, no sun disc) ------------------- */

    public static final Function baseB = new Function.Def(VEC3, "sky_baseB") {{
	Expression d = param(IN, VEC3).ref();
	Expression s = param(IN, VEC3).ref();
	/* The sun's real elevation, from sky_sunh -- same reason baseA takes
	 * it, and the defect ADR-0010 left open here. */
	Expression sh = param(IN, FLOAT).ref();
	/* sky_morning, for the same reason again. This was hoisted when the
	 * function ran six times per fragment -- once from colB and five times
	 * from the horizon fog -- so the gate could not be recomputed six
	 * times over. Only colB is left, so the hoist no longer saves
	 * anything; it stays because passing the value in is what keeps this
	 * function pure maths over its parameters, which the file's header
	 * asks of every function here. */
	Expression am = param(IN, FLOAT).ref();
	/* 0.98 was a cloud's asymmetry, not an atmosphere's. Measured on the
	 * phase function it took half the sun's brightness away within ONE
	 * degree and 97% within five, so the sun had no halo at all -- just a
	 * needle, which rendered as a red streak at sunset rather than a glow.
	 * 0.76 is the usual figure for atmospheric aerosol and widens the
	 * half-brightness point from about 1 degree to about 12. */
	code.add(raw("const float sk_Br = 0.0025, sk_Bm = 0.0003, sk_g = 0.76;\n" +
		     "vec3 sk_nitro = vec3(0.650, 0.570, 0.475);\n" +
		     "vec3 sk_Kr = sk_Br / pow(sk_nitro, vec3(4.0));\n" +
		     "vec3 sk_Km = sk_Bm / pow(sk_nitro, vec3(0.84));\n" +
		     "vec3 sk_pos = $0;\n" +
		     /* Clamping y to 0 turns a straight-down direction into
		      * vec3(0), and normalize(vec3(0)) is NaN -- which then
		      * poisons the whole returned colour. Reachable from colB
		      * through the cube's bottom face at maximum camera
		      * elevation (MapView.java:337 clamps telev to pi/2). */
		     "sk_pos.y = max(sk_pos.y, 1.0e-4);\n" +
		     "float sk_mu = dot(normalize(sk_pos), $1);\n" +
		     "float sk_ray = 3.0 / (8.0 * 3.14159) * (1.0 + sk_mu * sk_mu);\n" +
		     "vec3 sk_mie = (sk_Kr + sk_Km * (1.0 - sk_g * sk_g) / (2.0 + sk_g * sk_g)\n" +
		     "               / pow(1.0 + sk_g * sk_g - 2.0 * sk_g * sk_mu, 1.5)) / (sk_Br + sk_Bm);\n" +
		     /* The extinction's sun-height factor is a physical falloff
		      * on the sun, so by ADR-0010 it must not read the
		      * compressed sine: $1.y tops out at sin(PEAK) = 0.46, so
		      * this term treated 16:24 -- the sun 27 real degrees up --
		      * as an 11-degree sun and applied near-sunset extinction
		      * to the whole afternoon. Crossed with sk_Kr's blue
		      * weighting that lands the visible band on green; the
		      * old over-exposure tonemapped it to white, and DAY_EXPO
		      * (ADR-0020) uncovered it. sin($2) is the same quantity
		      * decompressed.
		      *
		      * Above the horizon only. Both branches are 0 at sh = 0,
		      * so sunrise and sunset themselves do not move, and every
		      * sh <= 0 case -- both twilights and the night, where
		      * this term still has blend weight until NIGHT_EDGE -- is
		      * bit-identical by construction, which is what keeps
		      * dawn_check's HOLD family at 0.000e+00. */
		     "vec3 sk_day = exp(-exp(-((sk_pos.y + (($2 > 0.0) ? sin($2) : $1.y) * 4.0) * (exp(-sk_pos.y * 16.0) + 0.1) / 80.0) / sk_Br)\n" +
		     "              * (exp(-sk_pos.y * 16.0) + 0.1) * sk_Kr / sk_Br)\n" +
		     "              * exp(-sk_pos.y * exp(-sk_pos.y * 8.0) * 4.0) * exp(-sk_pos.y * 2.0) * 4.0;\n" +
		     /* Night is a colour, not a grey. The neutral term this
		      * replaced left the night sky reading as haze; a cool navy
		      * is what the approved prototype showed and what the stars
		      * need behind them to be legible.
		      *
		      * On sk_sh, not on sun.y: 1 - exp() of the compressed sine
		      * only ever reached 0.31, so the term barely grew as the
		      * sun sank. 0.038 is set so deep night lands where the
		      * variant chosen from the renders put it -- within 2 levels
		      * at 22:00, 00:00 and 02:00. */
		     /* sk_day is a function of elevation alone -- there is no
		      * azimuth anywhere inside it -- so the warm band it draws
		      * at sunrise closes a full ring around the sky. Neither
		      * term that does have direction breaks that: Rayleigh's
		      * 1 + mu*mu is symmetric, as bright behind you as ahead,
		      * and Mie is the needle above. Measured across four camera
		      * yaws the band came out the same to within 1%.
		      *
		      * So weight it toward the sun, and only while the sun is
		      * low -- at noon a ring IS the right answer, and the gate
		      * keeps it: 12:00 moves 0.11 of a level, 09:00 and 15:00
		      * move 0.45, while sunrise moves 18.
		      *
		      * The floor is 0.60 rather than something smaller because
		      * the horizon fog built its colour from this same
		      * function, and taking the away side much darker turned
		      * the map edge behind the player into a wall. That
		      * constraint died with the fog. The number stays: what it
		      * would become without the constraint is a look to be
		      * judged on screen, not arithmetic to be redone here. */
		     "float sk_low = exp(-abs($2) * 4.0);\n" +
		     "float sk_side = 0.60 + 0.40 * pow(clamp(sk_mu, 0.0, 1.0), 1.5);\n" +
		     "sk_day *= mix(1.0, sk_side, sk_low);\n" +
		     /* max() because 1 - exp() goes negative once the sun is up,
		      * and the blend still gives it weight between the horizon
		      * and NIGHT_EDGE -- the old line had the same wart and was
		      * subtracting radiance through the whole morning. */
		     /* Two nights, crossfaded on sky_morning. The afternoon
		      * side is the shipped expression, untouched, so dusk comes
		      * out to one ulp -- 8.9e-08 per pixel from 18:00 to 22:00,
		      * which is the gate's own float error, not a colour change.
		      * The morning side saturates NIGHT_RATE times faster and is
		      * scaled so both agree at midnight, where the crossfade
		      * turns. */
		     "vec3 sk_nit = vec3(0.45, 0.62, 1.05)\n" +
		     "              * mix(max(1.0 - exp($2), 0.0) * 0.038,\n" +
		     "                    max(1.0 - exp($2 * " + NIGHT_RATE + "), 0.0) * " + NIGHT_SCALE + ", $3);\n" +
		     /* The blend that made this mode have no night at all.
		      *
		      * -sun.y * 0.2 + 0.5 was written for a sun whose y spans
		      * -1 to +1. ADR-0006 compressed it to PEAK = 0.48, so
		      * abs(sun.y) could not exceed 0.46 and the factor was stuck
		      * between 0.408 and 0.592: 41% of the DAYLIGHT scattering
		      * survived at midnight, and 41% of the night term at noon.
		      * Measured at 02:27 the sky came out (144,139,122), a beige
		      * band brighter than this same model's own noon zenith.
		      *
		      * Now it spans its full range against real elevation, on
		      * NIGHT_EDGE: full night one such angle below the horizon,
		      * full day one above. 0.5 at the horizon is kept
		      * deliberately -- it is what sunset and sunrise were judged
		      * on, and both come out bit-identical to before.
		      *
		      * The scale was ASTRO and is the reason the sky finished
		      * its night an hour after the client's own calendar did.
		      * See NIGHT_EDGE for the measurement. */
		     "vec3 sk_out = sk_ray * sk_mie * mix(sk_day, sk_nit,\n" +
		     "              clamp(0.5 - $2 / " + (2.0 * NIGHT_EDGE) + ", 0.0, 1.0));\n" +
		     /* The one thing mode A had that this model has no
		      * equivalent of: a broad warm wash on the sun's side. Same
		      * shape as baseA's pow(dot, 5) dusk term and gated by the
		      * same TWI on the real elevation.
		      *
		      * Without it the sky cannot say where the sun is. Measured
		      * at sunrise, mean frame value facing the sun against
		      * facing away: mode A 1.21x, this model 1.02x. With this
		      * and the band weighting above it reaches 1.73x -- more
		      * one-sided than mode A, deliberately, because this sky is
		      * darker overall and a real sunrise is strongly one-sided.
		      *
		      * 0.10 rather than more: at 0.18 the near side filled with
		      * a heavy brown rather than a wash. */
		     /* Same crossfade. The morning falloff is wide enough to
		      * still be worth something an hour before the disc; the
		      * evening keeps TWI, which is what ADR-0015 was tuned on.
		      * Same 0.10 on both sides -- it is the value at the
		      * horizon, and the horizon is where both ends were
		      * judged. */
		     "sk_out += vec3(1.0, 0.42, 0.13) * pow(clamp(sk_mu, 0.0, 1.0), 5.0)\n" +
		     "          * (1.0 - clamp($0.y, 0.0, 1.0))\n" +
		     "          * mix(exp(-abs($2) * " + TWI + ") * 0.10,\n" +
		     "                exp(-abs($2) * " + TWI_DAWN + ") * 0.10, $3);\n" +
		     /* Same below-horizon continuation as baseA -- see the
		      * note there. sk_pos.y was already clamped positive, so
		      * sk_out holds the horizon value for downward rays. */
		     "return mix(sk_out, sk_out * vec3(0.55, 0.54, 0.52),\n" +
		     "           pow(clamp(-$0.y, 0.0, 1.0), 0.7));\n", d, s, sh, am));
    }};

    /* --- clouds (Y-up) ----------------------------------------------- */

    public static final Function clouds = new Function.Def(VEC3, "sky_clouds") {{
	Expression d = param(IN, VEC3).ref();
	Expression s = param(IN, VEC3).ref();
	Expression t = param(IN, FLOAT).ref();
	Expression sky = param(IN, VEC3).ref();
	Expression oct = param(IN, INT).ref();
	Expression sh = param(IN, FLOAT).ref();
	/* The 0.45 is the cloud deck's height over its own distance: raising
	 * it from 0.12 stops the pattern piling up at the horizon and spreads
	 * it across the band that is actually on screen.
	 *
	 * The 4.5 is frequency. 1.6 put well under one noise cell on screen, so
	 * the clouds resolved to a single flat wash, and 2.0 was barely better:
	 * the visible band spans 2.6 by 1.6 cells there, so one cloud is a blur
	 * covering half the screen with no shape to read. 4.5 gives several
	 * masses with sky between them. (6.0 goes too far the other way, into
	 * roughly twenty features across the window, which reads as grain.) */
	/* The wind is added OUTSIDE the frequency multiply, and that placement
	 * is load-bearing. It used to be inside, which tied drift speed to
	 * cloud size: raising the frequency from 2.0 to 4.5 to give the clouds
	 * shape silently made them drift 2.25 times faster as well. Keep them
	 * separate so either can be tuned without touching the other.
	 *
	 * The constants are in noise cells per GAME second, and game time runs
	 * fast: measured 3.56 game-seconds per real second off the in-game
	 * clock (15:12:10 to 15:12:38 across a 7.87 s capture), which matches
	 * Glob.itimefac = 3.0 plus the server's own rate.
	 *
	 * The old 0.010 therefore worked out at 0.045 * 3.56 = 0.160 cells per
	 * REAL second. The visible band is about 4.3 cells wide, so a cloud
	 * crossed the screen in 27 seconds -- measured independently by block
	 * correlation on a capture at 65 to 90 px/s. Real clouds take minutes:
	 * a deck at 2 km under a 10 m/s wind sweeps this 53-degree field in
	 * roughly 380 s. 0.0050 gives 243 s, close to that and still plainly
	 * moving -- 7.8 px/s, so a cloud shifts its own width in about a
	 * minute. */
	code.add(raw("if($0.y <= 0.005) return $3;\n" +
		     "vec2 sk_uv = $0.xz / ($0.y + 0.45) * (0.55 * 4.5) + vec2($2 * 0.0050, $2 * 0.0020);\n" +
		     "float sk_p = 0.0;\n" +
		     "{\n" +
		     "    vec2 sk_q = sk_uv;\n" +
		     "    float sk_amp = 0.5;\n" +
		     "    for(int sk_o = 0; sk_o < 8; sk_o++) {\n" +
		     "        if(sk_o >= $4) break;\n" +
		     "        vec2 sk_i = floor(sk_q), sk_f = fract(sk_q);\n" +
		     "        vec2 sk_u = sk_f * sk_f * (3.0 - 2.0 * sk_f);\n" +
		     "        float sk_h0 = fract(sin(dot(sk_i + vec2(0.0, 0.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        float sk_h1 = fract(sin(dot(sk_i + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        float sk_h2 = fract(sin(dot(sk_i + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        float sk_h3 = fract(sin(dot(sk_i + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        sk_p += sk_amp * mix(mix(sk_h0, sk_h1, sk_u.x), mix(sk_h2, sk_h3, sk_u.x), sk_u.y);\n" +
		     "        sk_q *= 2.03;\n" +
		     "        sk_amp *= 0.5;\n" +
		     "    }\n" +
		     "}\n" +
		     /* Thresholds measured over 300k samples of this noise
		      * (four octaves: mean 0.469, standard deviation 0.123).
		      *
		      * 0.48 to 0.86 was the prototype's: the upper bound sat
		      * above the noise's own maximum, so coverage came out at
		      * 5.8% and the sky was in practice cloudless. 0.38 to 0.58
		      * fixed the coverage but the ramp was 1.6 standard
		      * deviations wide, so 30% of the sky landed at a partial
		      * value -- neither cloud nor clear, which renders as a
		      * diffuse veil rather than as clouds. This window is half
		      * a standard deviation: 30% coverage, 8% partial. */
		     "float sk_c = smoothstep(0.51, 0.57, sk_p);\n" +
		     "float sk_fade = smoothstep(0.0, 0.10, $0.y);\n" +
		     /* The lit colour has to be well over 1, and this is why:
		      * tone() is Reinhard with a white point of 0.72, so a cloud
		      * at 1.0 linear lands within a couple of levels of the sky
		      * it sits on. Measured on the day sky at the elevations the
		      * camera actually shows -- sky (151, 169, 191), cloud
		      * (177, 177, 179) facing away from the sun -- the whole
		      * difference was 10 levels of luminance, and mostly a loss
		      * of saturation rather than a gain of brightness. Clouds
		      * were being drawn over about 40% of the sky and read as
		      * flat grey. At 2.2 the same comparison gives 29 levels
		      * away from the sun and 53 toward it.
		      *
		      * The bias drops 0.62 to 0.55 so the lit term spans more of
		      * its range instead of saturating; that is what puts shape
		      * inside a cloud rather than one even tone. */
		     "float sk_lit = clamp(dot(normalize(vec3($0.x, 0.35, $0.z)), $1) * 0.5 + 0.55, 0.0, 1.0);\n" +
		     "float sk_day = clamp($5 * 3.0 + 0.35, 0.05, 1.0);\n" +
		     "vec3 sk_cc = mix(vec3(0.30, 0.32, 0.40), vec3(2.20, 2.13, 2.02), sk_lit) * sk_day;\n" +
		     /* Wider than the sky's own band -- cloud tops keep the sun
		      * after the ground has lost it -- but on the real elevation,
		      * not the compressed sine. On the sine this term was the one
		      * painting the clouds sunset-orange at 03:37 game time. */
		     "sk_cc = mix(sk_cc, sk_cc * vec3(1.25, 0.85, 0.62),\n" +
		     "            exp(-abs($5) * " + TWI_CLOUD + ") * 0.85);\n" +
		     "return mix($3, sk_cc, sk_c * sk_fade * 0.88);\n",
		     d, s, t, sky, oct, sh));
    }};

    /* --- clouds, quality mode (Y-up) ---------------------------------- */

    /* What this has that sky_clouds does not is one thing: a SECOND sample of
     * the same field, displaced toward the sun. Where density rises toward the
     * sun, the point is behind cloud and is shaded. That is the only term in
     * either cloud function whose shading follows the sun, and it is what makes
     * a cloud read as a mass rather than as a patch. Measured on the opaque
     * interior over six fixed views at mid sun, against the shader this
     * replaces: median spread 16 levels of 255 against 9 facing away from the
     * sun, and 13 against 6 facing it, with the worst view going from 1 level
     * to 10. The floor is the point -- a cloud spanning one level of 255 has
     * no internal pattern at all.
     *
     * Do NOT reach for a sun-azimuth variance statistic to prove this. Three
     * formulations were tried and none separates a cloud having a shaded side
     * that moves from the sky being brighter on one side, because sk_lit reads
     * d.x and d.z and draws a sky-wide gradient that survives both a mean
     * subtraction and a cloud-scale high-pass. On the rawest of the three this
     * function scores 0.0099 and the flat card it replaces scores 0.0366.
     * ADR-0013 records all three attempts.
     *
     * The second sample is taken over the first CLOUD_SUN_OCT octaves only; see
     * that constant for why, and for what it costs.
     *
     * It is a separate function rather than a flag on sky_clouds because the
     * loop body genuinely differs -- a different basis and two accumulators --
     * and because keeping sky_clouds untouched means Mode A cannot regress,
     * which is provable by byte-comparing the GLSL SkyDump emits for sky_colA.
     *
     * There is no octave parameter. Not for speed -- both call sites already
     * pass compile-time literals -- but because every argument removed is one
     * less place for raw()'s unchecked $n substitution to go wrong, which
     * ADR-0009 records as this DSL's characteristic failure mode. */
    public static final Function cloudsB = new Function.Def(VEC3, "sky_cloudsB") {{
	Expression d = param(IN, VEC3).ref();
	Expression s = param(IN, VEC3).ref();
	Expression t = param(IN, FLOAT).ref();
	Expression sky = param(IN, VEC3).ref();
	Expression sh = param(IN, FLOAT).ref();
	code.add(raw("if($0.y <= 0.005) return $3;\n" +
		     "vec2 sk_uv = $0.xz / ($0.y + 0.45) * (0.55 * " + CLOUD_FREQ + ")\n" +
		     "           + vec2($2 * " + CLOUD_DRIFT_X + ", $2 * " + CLOUD_DRIFT_Y + ");\n" +
		     /* The sun's azimuth in the deck plane. PEAK = 0.48 bounds
		      * |s.y| at 0.462, so |s.xz| is never below 0.887 and this
		      * cannot degenerate -- the guard is there because that
		      * bound is a constant somewhere else, not a law. */
		     "vec2 sk_sd = $1.xz;\n" +
		     "float sk_sl = length(sk_sd);\n" +
		     "sk_sd = (sk_sl < 1.0e-4) ? vec2(1.0, 0.0) : (sk_sd / sk_sl);\n" +
		     "vec2 sk_o2 = sk_sd * " + CLOUD_REACH + ";\n" +
		     "float sk_p = 0.0;\n" +
		     /* Sun-displaced only through CLOUD_SUN_OCT octaves; above
		      * that it takes sk_p's own value -- see the guard below. */
		     "float sk_ps = 0.0;\n" +
		     "{\n" +
		     "    vec2 sk_q = sk_uv;\n" +
		     "    vec2 sk_q2 = sk_uv + sk_o2;\n" +
		     "    float sk_amp = 0.5;\n" +
		     "    for(int sk_o = 0; sk_o < 4; sk_o++) {\n" +
		     "        vec2 sk_i = floor(sk_q), sk_f = fract(sk_q);\n" +
		     "        vec2 sk_u = sk_f * sk_f * (3.0 - 2.0 * sk_f);\n" +
		     "        float sk_h0 = fract(sin(dot(sk_i + vec2(0.0, 0.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        float sk_h1 = fract(sin(dot(sk_i + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        float sk_h2 = fract(sin(dot(sk_i + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "        float sk_h3 = fract(sin(dot(sk_i + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     /* abs(2n-1) is the whole silhouette change. It costs one
		      * multiply-add and a sign strip, and it turns amorphous
		      * patches into rounded lobes. It also moves the field's
		      * mean from 0.485 to 0.335, which is why the thresholds
		      * above are not sky_clouds'. */
		     "        float sk_n = mix(mix(sk_h0, sk_h1, sk_u.x), mix(sk_h2, sk_h3, sk_u.x), sk_u.y);\n" +
		     "        sk_p += sk_amp * abs(2.0 * sk_n - 1.0);\n" +
		     /* Above CLOUD_SUN_OCT both accumulators take the same value,
		      * so those octaves cancel out of sk_ps - sk_p exactly. The
		      * loop bounds are constant, so both drivers unroll it and
		      * fold this comparison -- the saving is real work removed,
		      * not a branch traded for arithmetic. */
		     "        if(sk_o < " + CLOUD_SUN_OCT + ") {\n" +
		     "            vec2 sk_i2 = floor(sk_q2), sk_f2 = fract(sk_q2);\n" +
		     "            vec2 sk_u2 = sk_f2 * sk_f2 * (3.0 - 2.0 * sk_f2);\n" +
		     "            float sk_g0 = fract(sin(dot(sk_i2 + vec2(0.0, 0.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "            float sk_g1 = fract(sin(dot(sk_i2 + vec2(1.0, 0.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "            float sk_g2 = fract(sin(dot(sk_i2 + vec2(0.0, 1.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "            float sk_g3 = fract(sin(dot(sk_i2 + vec2(1.0, 1.0), vec2(127.1, 311.7))) * 43758.5453123);\n" +
		     "            float sk_m = mix(mix(sk_g0, sk_g1, sk_u2.x), mix(sk_g2, sk_g3, sk_u2.x), sk_u2.y);\n" +
		     "            sk_ps += sk_amp * abs(2.0 * sk_m - 1.0);\n" +
		     "        } else {\n" +
		     "            sk_ps += sk_amp * abs(2.0 * sk_n - 1.0);\n" +
		     "        }\n" +
		     "        sk_q *= 2.03;\n" +
		     "        sk_q2 *= 2.03;\n" +
		     "        sk_amp *= 0.5;\n" +
		     "    }\n" +
		     "}\n" +
		     "float sk_c = smoothstep(" + CLOUD_ON + ", " + CLOUD_FULL + ", sk_p);\n" +
		     "float sk_dep = smoothstep(" + CLOUD_FULL + ", " + CLOUD_CORE + ", sk_p);\n" +
		     "float sk_fade = smoothstep(0.0, 0.10, $0.y);\n" +
		     /* As shipped: a hemispheric wash that varies across the sky
		      * rather than across a cloud. Kept at low weight because it
		      * is the only term that says anything at all where the two
		      * samples happen to agree. */
		     "float sk_lit = clamp(dot(normalize(vec3($0.x, 0.35, $0.z)), $1) * 0.5 + 0.55, 0.0, 1.0);\n" +
		     /* Thin edges transmit, thick core blocks. */
		     /* 0.35 rather than 0.55, and the pair below with it, is the
		      * "C2" step of an intensity ladder rendered against the
		      * shipped shader and picked by eye. Each step varied these
		      * two, the mix below them, AND the dark endpoint of the
		      * colour ramp -- which is why these figures do not appear
		      * until the colour block further down lands as well. Measured
		      * at midday facing away from the sun:
		      *
		      *   C1  sep 11.2  relief 24.7  darkest interior pixel 192
		      *   C2  sep 15.4  relief 38.8  darkest interior pixel 172
		      *   C3  sep 20.5  relief 54.2  darkest interior pixel 156
		      *
		      * The bound is the darkest pixel, not the spread: below about
		      * 170 of 255 the mass stops reading as a cloud and starts
		      * reading as rain. C3 crosses it. */
		     "float sk_lv = mix(1.0, 0.35, sk_dep);\n" +
		     /* The gain is 2.5 rather than something steeper on purpose.
		      * At 7.0 this term saturated at one bound or the other for
		      * 70 to 94% of interior pixels, which is a binary mask, not
		      * shading.
		      *
		      * Raised to 4.0 with the two lines around it, as the "C2"
		      * step above. 4.0 is well short of where that saturation set
		      * in and buys most of the relief: 13.6 to 38.8 at midday. */
		     "float sk_dir = clamp(0.5 - (sk_ps - sk_p) * 4.0, 0.0, 1.0);\n" +
		     /* The top is exactly 1.00 and must stay there. An earlier
		      * revision used 1.18, which makes sk_lv reach 1.18, and
		      * 0.85 * 1.18 = 1.003 exceeds the clamp below. Measured
		      * facing the sun, 42.6% of interior pixels pinned at the
		      * ceiling and the internal spread collapsed to 2 levels of
		      * 255 -- worse than the shader this replaces. A factor
		      * feeding a clamped sum must not carry headroom above what
		      * the clamp admits, or the clamp deletes every other term. */
		     "sk_lv *= mix(0.10, 1.00, sk_dir);\n" +
		     "float sk_L = clamp(sk_lit * 0.15 + sk_lv * 0.85, 0.0, 1.0);\n" +
		     "float sk_day = clamp($4 * 3.0 + 0.35, 0.05, 1.0);\n" +
		     /* The hue comes from sk_hue and not from the endpoints, and
		      * that is the whole of this change.
		      *
		      * Three tinted variants were built with the tint weighted by
		      * (1 - sk_L) and all three were rejected on look. Probing
		      * sk_L out of the emitted shader says why: the cloud interior
		      * lives at 0.09 to 0.78 with a MEDIAN OF 0.33, so that weight
		      * is 0.67 on the median pixel. None of them was a shadow
		      * tint; all three tinted the whole cloud, and the blue one
		      * traded a rain sheet for a cold front.
		      *
		      * So the hue is confined by a smoothstep placed on that
		      * measured distribution: full blue below the interior's 5th
		      * percentile, gone before its median. The body stays white,
		      * and blue then reads as "lit by the sky" rather than as
		      * weather. Anyone who changes the shading above moves this
		      * distribution and must re-measure it and re-place all four
		      * of these numbers -- same rule ADR-0008 sets for the noise
		      * thresholds, and for the same reason.
		      *
		      * The warm note is the sunshine cue. It rides sk_dir, the one
		      * term that knows where the sun is, and is switched off both
		      * in the deep shadow (sk_hs) and at the white top (the second
		      * smoothstep), so it appears only on the turn of a lobe
		      * toward the sun. Hue CONTRAST, warm against blue, is what no
		      * all-cool variant could produce.
		      *
		      * sk_Lc lifts the mid-tones only -- sk_L * (1 - sk_L) peaks
		      * at 0.5 and vanishes at both ends -- so the mass sits at the
		      * sky's own level without the deep shadow moving. Measured as
		      * the signed median, cloud minus sky, at midday facing away:
		      * shipped +3.0, this shading without the lift -3.3, with it
		      * -1.3.
		      *
		      * sk_Lc cannot exceed 1.0: its derivative 1 + 0.45(1 - 2L) is
		      * positive throughout, so it is monotone and reaches its
		      * maximum at sk_L = 1, where the lift term is zero. The
		      * ceiling is therefore 4.91, just under the 5.00 it replaces,
		      * and the headroom warning above still holds. */
		     "float sk_hs = smoothstep(0.08, 0.40, sk_L);\n" +
		     "vec3 sk_hue = mix(vec3(0.68, 0.96, 1.50), vec3(1.0), sk_hs);\n" +
		     "sk_hue *= mix(vec3(1.0), vec3(1.10, 1.00, 0.87),\n" +
		     "              sk_dir * sk_hs * (1.0 - smoothstep(0.55, 0.95, sk_L)));\n" +
		     "float sk_Lc = sk_L + 0.45 * sk_L * (1.0 - sk_L) * sk_hs;\n" +
		     "vec3 sk_cc = mix(0.11, 4.91, sk_Lc) * sk_hue * sk_day;\n" +
		     /* No rim term here. One was tried -- a pow(dot(fake normal,
		      * sun), 6.0) forward-scatter highlight on the thin edges --
		      * and measured on the target integrated GPU it cost 0.13 ms
		      * per 1080p frame, better than a fifth of this whole layer's
		      * budget, to lift the thin ramp by a median of 0.6 levels of
		      * 255 with the sun ahead and 0.0 with it behind. Peak lift was
		      * 5.7 levels facing the sun against 5.6 facing away, which is
		      * a sun-facing term that cannot tell the two directions apart.
		      * Rendered with and without, side by side at mid and low sun,
		      * the two are indistinguishable. */
		     /* The twilight tint is multiplicative on the result, and
		      * that was re-decided rather than inherited. Raising the lit
		      * peak to 5.00 does flatten colour ratios near white --
		      * sky_tone's derivative is four times shallower there than
		      * at 2.20, and on a fully lit pixel this tint drops from 22
		      * levels of 255 to 12. But a fully lit pixel is not what a
		      * cloud is made of: measured at the horizon, the brightest
		      * tenth of the interior carries 21.6 levels against the
		      * interior median's 24.1, a loss of a tenth.
		      *
		      * The alternative -- tinting the mix endpoints, so the ratio
		      * is carried where the tonemap can resolve it -- was built
		      * and measured. It wins at the horizon and loses at every
		      * other point on the ramp, because tinting endpoints makes
		      * the tint's strength depend on sk_L, and it turns the
		      * horizon clouds brown by darkening the lit end to open the
		      * ratio. ADR-0013 has the four-elevation table. */
		     "sk_cc = mix(sk_cc, sk_cc * vec3(1.25, 0.85, 0.62),\n" +
		     "            exp(-abs($4) * " + TWI_CLOUD + ") * 0.85);\n" +
		     "return mix($3, sk_cc, sk_c * sk_fade * 0.95);\n",
		     d, s, t, sky, sh));
    }};

    /* --- public entry points (Z-up in, tonemapped out) --------------- */

    public static final Function colA = new Function.Def(VEC3, "sky_colA") {{
	Expression wd = param(IN, VEC3).ref();
	Expression ws = param(IN, VEC3).ref();
	Expression night = param(IN, FLOAT).ref();
	Expression t = param(IN, FLOAT).ref();
	Expression e = param(IN, FLOAT).ref();
	Expression g = param(IN, FLOAT).ref();
	Expression d = id("sk_d"), s = id("sk_s"), col = id("sk_col");
	/* cos of the ray's true world elevation -- declared by the rebuild
	 * below, and read before the rebuild overwrites sk_d. */
	Expression ch = id("sk_hl");
	/* The sun's real elevation, computed once here and handed to everything
	 * below that models an atmosphere. See sky_sunh. */
	Expression sh = id("sk_sh");
	code.add(raw("vec3 sk_d = $0;\n" +
		     "vec3 sk_s = $1;\n" +
		     "float sk_sh = $2;\n" +
		     /* Rebuild the ray: elevation from sky_elev, azimuth kept
		      * from the world. An earlier revision used the elevation's
		      * SINE here rather than its angle, which put the top of
		      * the screen near the zenith and squeezed the whole
		      * azimuth circle -- clouds and stars then covered a
		      * fraction of one noise cell and rendered as flat wash. */
		     "float sk_e = clamp($9, -1.5533, 1.5533);\n" +
		     "vec2 sk_hz = vec2(sk_d.x, sk_d.z);\n" +
		     "float sk_hl = length(sk_hz);\n" +
		     "sk_hz = (sk_hl < 1.0e-5) ? vec2(1.0, 0.0) : (sk_hz / sk_hl);\n" +
		     "sk_hz *= cos(sk_e);\n" +
		     "sk_d = vec3(sk_hz.x, sin(sk_e), sk_hz.y);\n" +
		     "vec3 sk_col = $3 + $4 + $5;\n" +
		     "sk_col = $6;\n" +
		     "return mix($7, vec3(1.0), $8);\n",
		     yup.call(wd), yup.call(ws), sunh.call(s),
		     baseA.call(d, s, sh), disc.call(d, s, g, ch, sh),
		     stars.call(d, s, t, g, sh),
		     clouds.call(d, s, t, col, Cons.l(4), sh),
		     tone.call(col), night, e));
    }};

    public static final Function colB = new Function.Def(VEC3, "sky_colB") {{
	Expression wd = param(IN, VEC3).ref();
	Expression ws = param(IN, VEC3).ref();
	Expression night = param(IN, FLOAT).ref();
	Expression t = param(IN, FLOAT).ref();
	Expression e = param(IN, FLOAT).ref();
	Expression g = param(IN, FLOAT).ref();
	Expression d = id("sk_d"), s = id("sk_s"), col = id("sk_col");
	/* cos of the ray's true world elevation -- declared by the rebuild
	 * below, and read before the rebuild overwrites sk_d. */
	Expression ch = id("sk_hl");
	Expression sh = id("sk_sh");
	/* One sky_morning for the whole fragment, as sk_sh is one asin. */
	Expression am = id("sk_am");
	code.add(raw("vec3 sk_d = $0;\n" +
		     "vec3 sk_s = $1;\n" +
		     "float sk_sh = $2;\n" +
		     "float sk_am = $10;\n" +
		     /* Same rebuild as colA -- see the note there. */
		     "float sk_e = clamp($9, -1.5533, 1.5533);\n" +
		     "vec2 sk_hz = vec2(sk_d.x, sk_d.z);\n" +
		     "float sk_hl = length(sk_hz);\n" +
		     "sk_hz = (sk_hl < 1.0e-5) ? vec2(1.0, 0.0) : (sk_hz / sk_hl);\n" +
		     "sk_hz *= cos(sk_e);\n" +
		     "sk_d = vec3(sk_hz.x, sin(sk_e), sk_hz.y);\n" +
		     /* The exposure multiplies the ATMOSPHERE alone. The disc,
		      * the stars and the clouds keep the levels they were
		      * judged at -- and the clouds gain by it, since the sky
		      * they sit on stops being as bright as they are. See
		      * DAY_EXPO. */
		     "vec3 sk_col = ($3 * mix(1.0, " + DAY_EXPO +
		     ", clamp(sk_sh / " + DAY_EXPO_UP + ", 0.0, 1.0))) + $4 + $5;\n" +
		     "sk_col = $6;\n" +
		     "return mix($7, vec3(1.0), $8);\n",
		     yup.call(wd), yup.call(ws), sunh.call(s),
		     baseB.call(d, s, sh, am), disc.call(d, s, g, ch, sh),
		     stars.call(d, s, t, g, sh),
		     cloudsB.call(d, s, t, col, sh),
		     tone.call(col), night, e, morning.call(s)));
    }};
}
