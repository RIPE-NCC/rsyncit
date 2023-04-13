package net.ripe.rpki.rsyncit.rrdp;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.rsyncit.config.AppConfig;
import net.ripe.rpki.rsyncit.util.http.WebClientBuilderFactory;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;

@Component
@Slf4j
public class RrdpFetchJob extends QuartzJobBean  {

    private final WebClientBuilderFactory webClientFactory;
    private final AppConfig appConfig;

    @Autowired
    public RrdpFetchJob(WebClientBuilderFactory webClientFactory, AppConfig appConfig) {
        this.webClientFactory = webClientFactory;
        this.appConfig = appConfig;
    }

    @Bean("Rrdp_Fetch_Job_Detail")
    public JobDetail jobDetail() {
        return JobBuilder.newJob().ofType(RrdpFetchJob.class)
            .storeDurably()
            .withIdentity("Rrdp_Fetch_Job_Detail")
            .withDescription("Invoke Rrdp Fetch service...")
            .build();
    }

    @Bean("Rrdp_Fetch_Job_Trigger")
    public Trigger trigger(
        @Qualifier("Rrdp_Fetch_Job_Detail") JobDetail job) {
        return
            TriggerBuilder.newTrigger().forJob(job)
                .withIdentity("Rrdp_Fetch_Job_Trigger")
                .withDescription("Rrdp Expiration trigger")
                .withSchedule(cronSchedule(appConfig.getCron()))
                .build();
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        try {
            final RrdpFetcher rrdpFetcher = new RrdpFetcher(appConfig.getConfig(), webClientFactory.builder().build());
            final Map<String, RpkiObject> objects = rrdpFetcher.fetchObjects();
            log.info("Fetched {} objects", objects.size());
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }
}
