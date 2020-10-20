/*
 * Copyright 2020 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.jmix.email.impl;

import com.google.common.base.Strings;
import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.global.TemplateHelper;
import com.haulmont.cuba.core.sys.AppContext;
import com.sun.mail.smtp.SMTPAddressFailedException;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.Resources;
import io.jmix.core.TimeSource;
import io.jmix.core.security.Authenticator;
import io.jmix.email.*;
import io.jmix.email.entity.SendingAttachment;
import io.jmix.email.entity.SendingMessage;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.annotation.Resource;
import javax.mail.internet.AddressException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component("email_Emailer")
public class EmailerImpl implements Emailer {

    private static final Logger log = LoggerFactory.getLogger(EmailerImpl.class);

    @Autowired
    protected EmailerProperties emailerProperties;

    protected AtomicInteger callCount = new AtomicInteger(0);

    @Resource(name = "mailSendTaskExecutor")
    protected TaskExecutor mailSendTaskExecutor;

    @Autowired
    protected TimeSource timeSource;

    @Autowired
    protected EmailDataProvider emailDataProvider;

    @Autowired
    protected Metadata metadata;

    @Autowired
    protected Authenticator authenticator;

    @Autowired
    protected EmailSender emailSender;

    @Autowired
    protected Resources resources;

    @Autowired
    protected MetadataTools metadataTools;

    @Override
    public void sendEmail(String address, String caption, String body, String bodyContentType,
                          EmailAttachment... attachment) throws EmailException {
        EmailInfo emailInfo = EmailInfoBuilder.create(address, caption, body)
                .setBodyContentType(bodyContentType)
                .setAttachments(attachment)
                .build();
        sendEmail(emailInfo);
    }

    @Override
    public void sendEmail(EmailInfo info) throws EmailException {
        prepareEmailInfo(info);
        persistAndSendEmail(info);
    }

    @Override
    public List<SendingMessage> sendEmailAsync(EmailInfo info) {
        //noinspection UnnecessaryLocalVariable
        List<SendingMessage> result = sendEmailAsync(info, null, null);
        return result;
    }

    @Override
    public List<SendingMessage> sendEmailAsync(EmailInfo info, Integer attemptsCount, Date deadline) {
        prepareEmailInfo(info);
        List<SendingMessage> messages = splitEmail(info, attemptsCount, deadline);
        emailDataProvider.persistMessages(messages, SendingStatus.QUEUE);
        return messages;
    }

    protected void prepareEmailInfo(EmailInfo emailInfo) {
        processBodyTemplate(emailInfo);

        if (emailInfo.getFrom() == null) {
            String defaultFromAddress = emailerProperties.getFromAddress();
            if (defaultFromAddress == null) {
                throw new IllegalStateException("jmix.email.fromAddress not set in the system");
            }
            emailInfo.setFrom(defaultFromAddress);
        }
    }

    protected void processBodyTemplate(EmailInfo info) {
        String templatePath = info.getTemplatePath();
        if (templatePath == null) {
            return;
        }

        Map<String, Serializable> params = info.getTemplateParameters() == null
                ? Collections.emptyMap()
                : info.getTemplateParameters();
        String templateContents = resources.getResourceAsString(templatePath);
        if (templateContents == null) {
            throw new IllegalArgumentException("Could not find template by path: " + templatePath);
        }
        //todo: template helper is not implemented yet
        String body = TemplateHelper.processTemplate(templateContents, params);
        info.setBody(body);
    }

    protected List<SendingMessage> splitEmail(EmailInfo info, @Nullable Integer attemptsCount, @Nullable Date deadline) {
        List<SendingMessage> sendingMessageList = new ArrayList<>();
        if (info.isSendInOneMessage()) {
            if (StringUtils.isNotBlank(info.getAddresses())) {
                SendingMessage sendingMessage = convertToSendingMessage(info.getAddresses(), info.getFrom(), info.getCc(),
                        info.getBcc(), info.getCaption(), info.getBody(), info.getBodyContentType(), info.getHeaders(),
                        info.getAttachments(), attemptsCount, deadline);

                sendingMessageList.add(sendingMessage);
            }
        } else {
            String[] splitAddresses = info.getAddresses().split("[,;]");
            for (String address : splitAddresses) {
                address = address.trim();
                if (StringUtils.isNotBlank(address)) {
                    SendingMessage sendingMessage = convertToSendingMessage(address, info.getFrom(), null,
                            null, info.getCaption(), info.getBody(), info.getBodyContentType(), info.getHeaders(),
                            info.getAttachments(), attemptsCount, deadline);

                    sendingMessageList.add(sendingMessage);
                }
            }
        }
        return sendingMessageList;
    }

    protected void sendSendingMessage(SendingMessage sendingMessage) {
        Objects.requireNonNull(sendingMessage, "sendingMessage is null");
        Objects.requireNonNull(sendingMessage.getAddress(), "sendingMessage.address is null");
        Objects.requireNonNull(sendingMessage.getCaption(), "sendingMessage.caption is null");
        Objects.requireNonNull(sendingMessage.getContentText(), "sendingMessage.contentText is null");
        Objects.requireNonNull(sendingMessage.getFrom(), "sendingMessage.from is null");
        try {
            emailSender.sendEmail(sendingMessage);
            emailDataProvider.updateStatus(sendingMessage, SendingStatus.SENT);
        } catch (Exception e) {
            log.warn("Unable to send email to '{}'", sendingMessage.getAddress(), e);
            SendingStatus newStatus = isNeedToRetry(e) ? SendingStatus.QUEUE : SendingStatus.NOTSENT;
            emailDataProvider.updateStatus(sendingMessage, newStatus);
        }
    }

    protected void persistAndSendEmail(EmailInfo emailInfo) throws EmailException {
        Objects.requireNonNull(emailInfo.getAddresses(), "addresses are null");
        Objects.requireNonNull(emailInfo.getCaption(), "caption is null");
        Objects.requireNonNull(emailInfo.getBody(), "body is null");
        Objects.requireNonNull(emailInfo.getFrom(), "from is null");

        List<SendingMessage> messages = splitEmail(emailInfo, null, null);

        List<String> failedAddresses = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        for (SendingMessage sendingMessage : messages) {
            SendingMessage persistedMessage = persistMessageIfPossible(sendingMessage);

            try {
                emailSender.sendEmail(sendingMessage);
                if (persistedMessage != null) {
                    emailDataProvider.updateStatus(persistedMessage, SendingStatus.SENT);
                }
            } catch (Exception e) {
                log.warn("Unable to send email to '{}'", sendingMessage.getAddress(), e);
                failedAddresses.add(sendingMessage.getAddress());
                errorMessages.add(e.getMessage());
                if (persistedMessage != null) {
                    emailDataProvider.updateStatus(persistedMessage, SendingStatus.NOTSENT);
                }
            }
        }

        if (!failedAddresses.isEmpty()) {
            throw new EmailException(failedAddresses, errorMessages);
        }
    }

    /*
     * Try to persist message and catch all errors to allow actual delivery
     * in case of database or file storage failure.
     */
    @Nullable
    protected SendingMessage persistMessageIfPossible(SendingMessage sendingMessage) {
        // A copy of sendingMessage is created
        // to avoid additional overhead to load body and attachments back from FS
        try {
            SendingMessage clonedMessage = createClone(sendingMessage);
            emailDataProvider.persistMessages(Collections.singletonList(clonedMessage), SendingStatus.SENDING);
            return clonedMessage;
        } catch (Exception e) {
            log.error("Failed to persist message '{}'", sendingMessage.getCaption(), e);
            return null;
        }
    }

    protected SendingMessage createClone(SendingMessage srcMessage) {
        SendingMessage clonedMessage = metadataTools.copy(srcMessage);
        List<SendingAttachment> clonedList = new ArrayList<>();
        for (SendingAttachment srcAttach : srcMessage.getAttachments()) {
            SendingAttachment clonedAttach = metadataTools.copy(srcAttach);
            clonedAttach.setMessage(null);
            clonedAttach.setMessage(clonedMessage);
            clonedList.add(clonedAttach);
        }
        clonedMessage.setAttachments(clonedList);
        return clonedMessage;
    }

    @Override
    public String processQueuedEmails() {
        if (applicationNotStartedYet()) {
            return null;
        }

        int callsToSkip = emailerProperties.getDelayCallCount();
        int count = callCount.getAndAdd(1);
        if (count < callsToSkip) {
            return null;
        }

        String resultMessage;
        try {
            authenticator.begin(getEmailerLogin());
            try {
                resultMessage = sendQueuedEmails();
            } finally {
                authenticator.end();
            }
        } catch (Throwable e) {
            log.error("Error", e);
            resultMessage = e.getMessage();
        }
        return resultMessage;
    }

    protected boolean applicationNotStartedYet() {
        return !AppContext.isStarted();
    }

    protected String sendQueuedEmails() {
        List<SendingMessage> messagesToSend = emailDataProvider.loadEmailsToSend();

        messagesToSend.forEach(this::submitExecutorTask);

        if (messagesToSend.isEmpty()) {
            return "";
        }

        return String.format("Processed %d emails", messagesToSend.size());
    }

    protected boolean shouldMarkNotSent(SendingMessage sendingMessage) {
        Date deadline = sendingMessage.getDeadline();
        if (deadline != null && deadline.before(timeSource.currentTimestamp())) {
            return true;
        }

        Integer messageAttemptsLimit = sendingMessage.getAttemptsCount();
        int defaultLimit = emailerProperties.getDefaultSendingAttemptsCount();
        int attemptsLimit = messageAttemptsLimit != null ? messageAttemptsLimit : defaultLimit;
        //noinspection UnnecessaryLocalVariable
        boolean res = sendingMessage.getAttemptsMade() != null && sendingMessage.getAttemptsMade() >= attemptsLimit;
        return res;
    }

    protected void submitExecutorTask(SendingMessage msg) {
        try {
            Runnable mailSendTask = new EmailSendTask(msg, authenticator);
            mailSendTaskExecutor.execute(mailSendTask);
        } catch (RejectedExecutionException e) {
            emailDataProvider.updateStatus(msg, SendingStatus.QUEUE);
        } catch (Exception e) {
            log.error("Exception while sending email to '{}': ", msg.getAddress(), e);

            SendingStatus newStatus = isNeedToRetry(e) ? SendingStatus.QUEUE : SendingStatus.NOTSENT;
            emailDataProvider.updateStatus(msg, newStatus);
        }
    }

    @Override
    public void updateSession() {
        emailSender.updateSession();
    }

    protected SendingMessage convertToSendingMessage(String address, String from, @Nullable String cc, @Nullable String bcc, String caption, String body,
                                                     String bodyContentType,
                                                     @Nullable List<EmailHeader> headers,
                                                     @Nullable List<EmailAttachment> attachments,
                                                     @Nullable Integer attemptsCount, @Nullable Date deadline) {
        SendingMessage sendingMessage = metadata.create(SendingMessage.class);

        sendingMessage.setAddress(address);
        sendingMessage.setCc(cc);
        sendingMessage.setBcc(bcc);
        sendingMessage.setFrom(from);
        sendingMessage.setContentText(body);
        sendingMessage.setCaption(caption);
        sendingMessage.setAttemptsCount(attemptsCount);
        sendingMessage.setDeadline(deadline);
        sendingMessage.setAttemptsMade(0);

        if (Strings.isNullOrEmpty(bodyContentType)) {
            bodyContentType = getContentBodyType(sendingMessage);
        }
        sendingMessage.setBodyContentType(bodyContentType);

        if (CollectionUtils.isNotEmpty(attachments)) {
            StringBuilder attachmentsName = new StringBuilder();
            List<SendingAttachment> sendingAttachments = new ArrayList<>(attachments.size());

            attachments.forEach(ea -> {
                attachmentsName.append(ea.getName()).append(";");

                SendingAttachment sendingAttachment = toSendingAttachment(ea);
                sendingAttachment.setMessage(sendingMessage);
                sendingAttachments.add(sendingAttachment);
            });

            sendingMessage.setAttachments(sendingAttachments);
            sendingMessage.setAttachmentsName(attachmentsName.toString());
        } else {
            sendingMessage.setAttachments(Collections.emptyList());
        }

        String headersLine = null;
        if (CollectionUtils.isNotEmpty(headers)) {
            headersLine = headers.stream()
                    .map(EmailHeader::toString)
                    .collect(Collectors.joining(SendingMessage.HEADERS_SEPARATOR));
        }
        sendingMessage.setHeaders(headersLine);

        replaceRecipientIfNecessary(sendingMessage);

        return sendingMessage;
    }

    protected String getContentBodyType(SendingMessage sendingMessage) {
        String bodyContentType;
        String text = sendingMessage.getContentText();
        bodyContentType = text.trim().startsWith("<html>") ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
        log.debug("Content body type is not set for email '{}' with addresses: {}. Will be used '{}'.",
                sendingMessage.getCaption(), sendingMessage.getAddress(), bodyContentType);
        return bodyContentType;
    }

    protected void replaceRecipientIfNecessary(SendingMessage msg) {
        if (emailerProperties.isSendAllToAdmin()) {
            String adminAddress = emailerProperties.getAdminAddress();
            log.warn("Replacing actual email recipient '{}' by admin address '{}'", msg.getAddress(), adminAddress);
            msg.setAddress(adminAddress);
        }
    }

    protected SendingAttachment toSendingAttachment(EmailAttachment ea) {
        SendingAttachment sendingAttachment = metadata.create(SendingAttachment.class);
        sendingAttachment.setContent(ea.getData());
        sendingAttachment.setContentId(ea.getContentId());
        sendingAttachment.setName(ea.getName());
        sendingAttachment.setEncoding(ea.getEncoding());
        sendingAttachment.setDisposition(ea.getDisposition());
        return sendingAttachment;
    }

    protected boolean isNeedToRetry(Exception e) {
        if (e instanceof MailSendException) {
            if (e.getCause() instanceof SMTPAddressFailedException) {
                return false;
            }
        } else if (e instanceof AddressException) {
            return false;
        }
        return true;
    }

    protected static class EmailSendTask implements Runnable {

        private SendingMessage sendingMessage;
        private static final Logger log = LoggerFactory.getLogger(EmailSendTask.class);
        private Authenticator authenticator;

        public EmailSendTask(SendingMessage message, Authenticator authenticator) {
            this.sendingMessage = message;
            this.authenticator = authenticator;
        }

        @Override
        public void run() {
            try {
                EmailerImpl emailer = AppBeans.get(EmailerImpl.class);
                authenticator.begin(emailer.getEmailerLogin());
                try {
                    emailer.sendSendingMessage(sendingMessage);
                } finally {
                    authenticator.end();
                }
            } catch (Exception e) {
                log.error("Exception while sending email to '{}': ", sendingMessage.getAddress(), e);
            }
        }
    }

    protected String getEmailerLogin() {
        return emailerProperties.getUserLogin();
    }
}