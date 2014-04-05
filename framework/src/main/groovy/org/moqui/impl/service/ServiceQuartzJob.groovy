/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.service

import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobDataMap
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ServiceQuartzJob implements Job {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceQuartzJob.class)

    void execute(JobExecutionContext jobExecutionContext) {
        String serviceName = jobExecutionContext.jobDetail.key.group

        JobDataMap jdm = jobExecutionContext.jobDetail.jobDataMap
        Map parameters = new HashMap()
        for (String key in jdm.getKeys()) parameters.put(key, jdm.get(key))

        if (logger.traceEnabled) logger.trace("Calling async|scheduled service [${serviceName}] with parameters [${parameters}]")

        ExecutionContext ec = Moqui.getExecutionContext()

        try {
            String userId = parameters.authUserAccount?.userId ?: parameters.authUsername
            String password = parameters.authUserAccount?.currentPassword ?: parameters.authPassword
            String tenantId = parameters.authTenantId
            // TODO: somehow run as user even if no password passed in, just for the special case of persisted jobs?
            if (userId && password) {
                ec.getUser().loginUser(userId, password, tenantId)
            } else if (tenantId) {
                ec.changeTenant(tenantId)
            }

            // logger.warn("=========== running quartz job for ${serviceName}, parameter tenantId=${tenantId}, active tenantId=${ec.getTenantId()}, parameters: ${parameters}")

            ec.service.sync().name(serviceName).parameters(parameters).call()
        } catch (Throwable t) {
            ec.message.addError(t.message)
            Throwable parent = t.cause
            while (parent != null) {
                ec.message.addError(parent.message)
                parent = parent.cause
            }
        }

        if (ec.getMessage().hasError()) {
            StringBuilder sb = new StringBuilder()
            sb.append("Error calling service [${serviceName}] with parameters [${parameters}]\n")
            sb.append(ec.getMessage().getErrorsString())
            logger.error(sb.toString())

            // TODO handle retry on error with max-retry?
        }

        ec.destroy()
    }
}
