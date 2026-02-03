package net.ripe.rpki.rsyncit.rrdp;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.AppConfig;
import net.ripe.rpki.rsyncit.service.SyncService;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

@Slf4j
public class RrdpFetchJob implements Job {

    // Static reference to SyncService since Quartz creates Job instances itself
    private static SyncService syncServiceInstance;

    public static void setSyncService(SyncService syncService) {
        syncServiceInstance = syncService;
    }

    public RrdpFetchJob() {
        // Default constructor required by Quartz
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            log.info("Starting scheduled RRDP sync job");
            syncServiceInstance.sync();
            log.info("Completed scheduled RRDP sync job");
        } catch (Exception e) {
            log.error("Error during scheduled RRDP sync", e);
            throw new JobExecutionException(e);
        }
    }

    public static Scheduler createAndStartScheduler(AppConfig appConfig, SyncService syncService) throws SchedulerException {
        setSyncService(syncService);

        SchedulerFactory schedulerFactory = new StdSchedulerFactory();
        Scheduler scheduler = schedulerFactory.getScheduler();

        JobDetail jobDetail = JobBuilder.newJob(RrdpFetchJob.class)
            .withIdentity("RrdpFetchJob", "rsyncit")
            .withDescription("Fetch RRDP repository and sync to rsync")
            .build();

        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("RrdpFetchTrigger", "rsyncit")
            .withDescription("Trigger for RRDP fetch job")
            .withSchedule(CronScheduleBuilder.cronSchedule(appConfig.getCron()))
            .build();

        scheduler.scheduleJob(jobDetail, trigger);
        scheduler.start();

        log.info("Scheduler started with cron expression: {}", appConfig.getCron());
        return scheduler;
    }
}
