package me.perdomo.dpc;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Periodic reconciliation.
 *
 * <p>Re-asserts the whole policy on a timer so that anything which drifted -
 * a restriction cleared by an OEM component, a package that appeared while a
 * broadcast was dropped, a maintenance window that expired - is corrected
 * without anyone touching the device.</p>
 */
public class EnforcementJobService extends JobService {

    private static final int JOB_ID = 4711;

    public static void schedule(Context ctx) {
        JobScheduler scheduler = ctx.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, EnforcementJobService.class))
                .setPeriodic(Policy.SWEEP_INTERVAL_MINUTES * 60_000L)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    public static void cancel(Context ctx) {
        JobScheduler scheduler = ctx.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // Cheap and non-blocking - safe to run on the main thread.
        if (PolicyManager.inMaintenanceWindow(this)) {
            Log.i(PolicyManager.TAG, "Sweep skipped - maintenance window open");
        } else if (PolicyManager.isDeviceOwner(this)) {
            PolicyManager.applyRestrictions(this);
            PolicyManager.applyHiddenPackages(this);
            PolicyManager.applyUninstallBlocked(this);
            PolicyManager.applyVpn(this);
            PolicyManager.enforceAllowlist(this);
            // Restart the watchdog if its process was killed. This is the only
            // thing that notices, so it is also the ceiling on how long
            // enforcement can stay degraded.
            WatchdogService.start(this);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
