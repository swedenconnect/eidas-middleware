/*
 * Copyright (c) 2022 Governikus KG. Licensed under the EUPL, Version 1.2 or as soon they will be approved by the
 * European Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work except in compliance
 * with the Licence. You may obtain a copy of the Licence at: http://joinup.ec.europa.eu/software/page/eupl Unless
 * required by applicable law or agreed to in writing, software distributed under the Licence is distributed on an
 * "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Licence for the
 * specific language governing permissions and limitations under the Licence.
 */

package de.governikus.eumw.poseidas.server.timer;

import static de.governikus.eumw.poseidas.server.timer.TimerValues.HOUR;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.Trigger;
import org.springframework.stereotype.Component;

import de.governikus.eumw.config.EidasMiddlewareConfig;
import de.governikus.eumw.config.TimerConfigurationType;
import de.governikus.eumw.config.TimerType;
import de.governikus.eumw.config.TimerUnit;
import de.governikus.eumw.poseidas.eidserver.crl.CertificationRevocationListImpl;
import de.governikus.eumw.poseidas.server.idprovider.config.ConfigurationService;
import de.governikus.eumw.poseidas.server.pki.TimerHistoryService;
import de.governikus.eumw.poseidas.server.pki.entities.TimerHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * This class manages the timer for the CRL renewal. The class implements the Runnable interface. The {@link #run() run}
 * method is used to renew CRLs. The {@link #getCrlTrigger(List) getCrlTrigger} method determines how often the
 * timer runs.
 *
 * @see ApplicationTimer
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CrlRenewalTimer implements Runnable
{

  private final ConfigurationService configurationService;

  private final TimerHistoryService timerHistoryService;

  @Override
  public void run()
  {
    log.debug("Execute CRL renewal");
    CertificationRevocationListImpl.RenewCrlsLists renewCrlsLists = new CertificationRevocationListImpl.RenewCrlsLists(new ArrayList<>(),
                                                                                                                       new ArrayList<>());

    if (CertificationRevocationListImpl.isInitialized())
    {
      renewCrlsLists = CertificationRevocationListImpl.getInstance().renewCrls();
    }
    else
    {
      renewCrlsLists.failed().add("Cannot execute CRL renewal timer task. CRL is not initialized");
      log.warn("Cannot execute CRL renewal timer task. CRL is not initialized");
    }

    if (null != renewCrlsLists && (!renewCrlsLists.succeeded().isEmpty() || !renewCrlsLists.failed().isEmpty()))
    {
      saveTimer(renewCrlsLists.succeeded(), renewCrlsLists.failed());
    }
    else
    {
      timerHistoryService.saveTimer(TimerHistory.TimerType.CRL_RENEWAL_TIMER, "CRL not required", true, true);
    }
  }

  Trigger getCrlTrigger(List<Instant> nextExecutions)
  {
    return triggerContext -> {
      log.debug("Handle trigger for CRL timer");
      Date lastExecution = triggerContext.lastScheduledExecutionTime();
      Date lastCompletion = triggerContext.lastCompletionTime();
      if (lastExecution == null || lastCompletion == null)
      {
        long initialDelay = 2 * HOUR;
        Date date = new Date(triggerContext.getClock().millis() + initialDelay);
        log.debug("First CRL timer task will executed with an initial delay of {} milliseconds", initialDelay);
        log.debug("CRL renewal timer task will be executed at {}", date);
        nextExecutions.add(date.toInstant());
        return date.toInstant();
      }
      Instant nextExecutiontime = lastCompletion.toInstant().plusMillis(getCrlRenewalTimer());
      nextExecutions.add(nextExecutiontime);
      Date date = Date.from(nextExecutiontime);
      log.debug("CRL renewal timer task will be executed at {}", date);
      return date.toInstant();
    };
  }

  private long getCrlRenewalTimer()
  {
    TimerConfigurationType timerConfiguration = configurationService.getConfiguration()
                                                                    .map(EidasMiddlewareConfig::getEidConfiguration)
                                                                    .map(EidasMiddlewareConfig.EidConfiguration::getTimerConfiguration)
                                                                    .orElse(null);
    // Check for configuration value
    if (timerConfiguration != null && timerConfiguration.getCrlRenewal() != null)
    {
      TimerType crlRenewal = timerConfiguration.getCrlRenewal();
      if (crlRenewal.getLength() != 0 && StringUtils.isNotBlank(crlRenewal.getUnit().value()))
      {
        TimerUnit unit = crlRenewal.getUnit();
        Integer length = crlRenewal.getLength();
        if (log.isDebugEnabled())
        {
          log.debug("Set timer value for CRL renewal to every {} {}", length, unit.value());
        }
        return ApplicationTimer.getUnitOfTime(crlRenewal.getUnit()) * crlRenewal.getLength();
      }
    }

    TimerUnit hour = TimerUnit.HOURS;
    int length = 2;
    if (log.isDebugEnabled())
    {
      log.debug("No timer configuration for CRL renewal timer present. Set timer to default value every {} {}",
                length,
                hour.value());
    }
    return ApplicationTimer.getUnitOfTime(hour) * length;
  }

  // Save timer execution results in database
  private void saveTimer(List<String> succeeded, List<String> failed)
  {
    StringBuilder timerExecutionMessage = new StringBuilder();
    if (!succeeded.isEmpty())
    {
      timerExecutionMessage.append("Succeeded: ").append(succeeded);
    }
    if (!failed.isEmpty())
    {
      if (!timerExecutionMessage.isEmpty())
      {
        timerExecutionMessage.append(System.lineSeparator()).append(System.lineSeparator());
      }
      timerExecutionMessage.append("Failed: ").append(failed);
    }
    timerHistoryService.saveTimer(TimerHistory.TimerType.CRL_RENEWAL_TIMER,
                                  timerExecutionMessage.toString(),
                                  failed.isEmpty(),
                                  true);
  }
}
