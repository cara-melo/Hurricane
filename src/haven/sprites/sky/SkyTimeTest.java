package haven.sprites.sky;

/* Self-check for SkyTime, in the shape Makefile's check target runs: a main
 * that prints what it checked and exits non-zero on a failure.
 *
 * The ten rows are real samples from ~/skyprobe.log, the ADR-0006 probe
 * campaign, spanning dt 0.00 to 0.96. They are embedded rather than read from
 * the log so the check is self-contained and survives the log being deleted.
 *
 * The tolerance is 0.03 rad. That is not a round number picked to pass: the
 * worst of the 175 usable rows in that log deviates by 0.022, and the failures
 * this guards against -- a flipped sign, a dropped 0.25, a missing wrap -- are
 * all worth more than a radian. A tolerance loose enough to hide them would
 * have to be forty times wider. */
public class SkyTimeTest {
    /* dt, and lightang as the server sent it at that dt. */
    private static final double[][] PROBE = {
	{0.0022, 1.5573},   /* 00:03 */
	{0.1115, 0.8922},   /* 02:40 */
	{0.1640, 0.5512},   /* 03:56 */
	{0.2236, 0.1770},   /* 05:21 */
	{0.2831, 6.0860},   /* 06:47 -- just past the wrap at dt 0.25 */
	{0.3392, 5.7338},   /* 08:08 */
	{0.8417, 2.5763},   /* 20:12 */
	{0.8940, 2.2491},   /* 21:27 */
	{0.9277, 2.0252},   /* 22:15 */
	{0.9619, 1.8104},   /* 23:05 */
    };
    private static final double TOL = 0.03;

    public static void main(String[] args) {
	int bad = 0;
	double worst = 0.0;
	for(double[] r : PROBE) {
	    double got = SkyTime.ang(r[0]);
	    double off = Math.abs(wrap(got - r[1]));
	    worst = Math.max(worst, off);
	    if(off > TOL) {
		System.err.format("ang(%.4f) = %.4f, probe says %.4f, off by %.4f%n",
				  r[0], got, r[1], off);
		bad++;
	    }
	}
	System.out.format("SkyTime.ang: %d probe rows, worst %.4f rad, tolerance %.2f%n",
			  PROBE.length, worst, TOL);

	/* Every minute the slider can produce must land inside one turn. */
	for(int i = 0; i <= 1440; i++) {
	    double a = SkyTime.ang(i / 1440.0);
	    if(!((a >= 0.0) && (a < (2 * Math.PI)))) {
		System.err.format("ang(%.5f) = %.4f, outside [0, 2pi)%n", i / 1440.0, a);
		bad++;
	    }
	}

	/* The override starts empty, takes a value, clips, and clears. */
	if(SkyTime.fake() != null) {
	    System.err.println("SkyTime did not start live");
	    bad++;
	}
	SkyTime.set(0.7292);
	if((SkyTime.fake() == null) || (Math.abs(SkyTime.fake() - 0.7292) > 1e-9)) {
	    System.err.println("SkyTime.set did not take");
	    bad++;
	}
	SkyTime.set(1.4);
	if((SkyTime.fake() == null) || (Math.abs(SkyTime.fake() - 1.0) > 1e-9)) {
	    System.err.println("SkyTime.set did not clip to 1.0");
	    bad++;
	}
	SkyTime.live();
	if(SkyTime.fake() != null) {
	    System.err.println("SkyTime.live did not clear");
	    bad++;
	}

	if(bad > 0) {
	    System.err.format("%d failures%n", bad);
	    System.exit(1);
	}
	System.out.println("SkyTime: ok");
    }

    /* Shortest signed distance between two angles, so the row at 06:47 -- which
     * sits just past the wrap -- is compared the short way round and not as a
     * near-2pi disagreement. */
    private static double wrap(double a) {
	double w = (a + Math.PI) % (2 * Math.PI);
	return(((w < 0) ? (w + (2 * Math.PI)) : w) - Math.PI);
    }
}
