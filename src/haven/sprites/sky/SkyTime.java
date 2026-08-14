package haven.sprites.sky;

import haven.Utils;

/* The clock the sky is drawn from when the player has taken it off the
 * server's.
 *
 * Normally this is empty and everything falls through: SkyPalette.from takes
 * the day fraction from Glob.ast and the bearing from Glob.lightang. SkyTimeWnd
 * fills it in so a chosen game minute can be looked at now. Waiting for one
 * costs up to 7.3 real hours -- a game day is 24 game hours at
 * GameUI.gameTimeSpeedMultiplier = 3.29 -- and comparing two sky modes at the
 * same minute costs it twice. Every sky record since ADR-0012 has been argued
 * from offline renders for that reason, and ADR-0016 closes on the admission
 * that none of it was ever seen in the client.
 *
 * What it moves is deliberately narrow: the drawn sun's elevation, and the
 * bearing the sun and the shadows share. Glob.lightelev and the three light
 * colours are NOT moved, because neither can be derived. ~/skyprobe.log holds
 * no lightelev sample between 09:00 and 18:00 -- exactly the hours this gets
 * used -- and the colours only ever arrive in the server's light message
 * (Glob.java:281-303). So the ground stays lit for the real hour and the window
 * says so on its face. Hiding that would make a screenshot taken here
 * indistinguishable from one taken in play, and the whole point is that they
 * are different kinds of evidence. See ADR-0022.
 *
 * Nothing here writes to Glob. */
public class SkyTime {
    /* null means live. volatile because SkyTimeWnd writes on the UI thread
     * while MapView.tick reads on the render thread -- the same reason and the
     * same pattern as SkyPalette.style. */
    private static volatile Double fake = null;

    public static Double fake() {
	return(fake);
    }

    public static void set(double dt) {
	fake = Utils.clip(dt, 0.0, 1.0);
    }

    public static void live() {
	fake = null;
    }

    /* Day fraction -> the sun's azimuth, in the frame Glob.lightang is measured
     * in, wrapped to [0, 2pi).
     *
     * This is the model ADR-0006 fitted and ADR-0016 checked: a full turn per
     * game day, crossing EAST_AZ at dt 0.25. dawn_check.py:262 carries the same
     * expression, and until this method existed that was the only written form
     * of it anywhere -- which is why ADR-0016 has to record that the harness and
     * the client agree "by construction". The definition lives here now and the
     * harness is the declared copy.
     *
     * Checked against the 180 rows of ~/skyprobe.log that carry ang: 175 fit
     * within 0.022 rad across dt 0.05 to 0.99, the other five being transients
     * inside Glob.ticklight's two-second blend. SkyTimeTest keeps ten of them. */
    public static double ang(double dt) {
	double a = (SkyPalette.EAST_AZ - (2 * Math.PI * (dt - 0.25))) % (2 * Math.PI);
	return((a < 0) ? (a + (2 * Math.PI)) : a);
    }
}
