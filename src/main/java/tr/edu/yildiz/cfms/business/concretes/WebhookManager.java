package tr.edu.yildiz.cfms.business.concretes;

import org.brunocvcunha.instagram4j.Instagram4j;
import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tr.edu.yildiz.cfms.api.controllers.ChatController;
import tr.edu.yildiz.cfms.api.dtos.apis.facebook.FacebookApiUserDto;
import tr.edu.yildiz.cfms.api.dtos.apis.telegram.TelegramApiFilePathDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.facebook.FacebookWebhookDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.facebook.FacebookWebhookDtoEntry;
import tr.edu.yildiz.cfms.api.dtos.webhooks.facebook.FacebookWebhookDtoMessage;
import tr.edu.yildiz.cfms.api.dtos.webhooks.instagram.InstagramConversationDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.telegram.TelegramWebhookDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.telegram.TelegramWebhookDtoMessage;
import tr.edu.yildiz.cfms.api.dtos.webhooks.twitter.TwitterWebhookDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.twitter.TwitterWebhookDtoEvent;
import tr.edu.yildiz.cfms.api.dtos.webhooks.twitter.TwitterWebhookDtoMessageCreate;
import tr.edu.yildiz.cfms.api.dtos.webhooks.twitter.TwitterWebhookDtoUser;
import tr.edu.yildiz.cfms.api.models.WebSocketClientConversation;
import tr.edu.yildiz.cfms.api.models.WebSocketClientMessage;
import tr.edu.yildiz.cfms.api.models.WebSocketClientMessages;
import tr.edu.yildiz.cfms.business.abstracts.WebhookService;
import tr.edu.yildiz.cfms.business.repository.ConversationRepository;
import tr.edu.yildiz.cfms.business.repository.MessageRepository;
import tr.edu.yildiz.cfms.core.enums.Platform;
import tr.edu.yildiz.cfms.entities.concretes.hibernate.Conversation;
import tr.edu.yildiz.cfms.entities.concretes.mongodb.MongoDbMessages;
import tr.edu.yildiz.cfms.entities.concretes.mongodb.MongoDbMessagesItem;
import tr.edu.yildiz.cfms.entities.concretes.mongodb.MongoDbMessagesAttachment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.time.Instant.ofEpochMilli;
import static java.util.TimeZone.getDefault;
import static tr.edu.yildiz.cfms.core.utils.Constants.*;
import static tr.edu.yildiz.cfms.core.utils.Utils.truncate;

@Service
public class WebhookManager implements WebhookService {
    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ChatController chatController;


    @Override
    public void handleFacebookWebhook(FacebookWebhookDto dto) {
        if (!dto.getObject().equals("page")) return;

        var entries = dto.getEntry();

        for (var entry : entries)
            processFacebookEntry(entry);

    }

    @Override
    public void handleTelegramWebhook(TelegramWebhookDto dto) {
        var message = dto.getMessage();
        if (message == null) return;

        processTelegramMessage(message);
    }

    @Override
    public void handleInstagramConversation(InstagramConversationDto dto, Boolean isNew) {

        List<MongoDbMessagesItem> messages = new ArrayList<>();

        for (var message : dto.getMessages()) {
            MongoDbMessagesItem mongoDbMessagesItem = new MongoDbMessagesItem();
            mongoDbMessagesItem.setText(message.getText());
            mongoDbMessagesItem.setSentDate(LocalDateTime.parse(message.getDate()));
            mongoDbMessagesItem.setId(message.getId());
            mongoDbMessagesItem.setSentByClient(message.getIsClient());
            messages.add(mongoDbMessagesItem);
        }

        Collections.reverse(messages);

        if (isNew) createInstagramConversation(dto, messages);
        else updateInstagramConversation(dto, messages);

    }

    @Override
    public Map<String, String> verifyTwitterWebhook(String crcToken) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(CONSUMER_SECRET.getBytes("UTF-8"), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            String hash = Base64.encodeBase64String(sha256_HMAC.doFinal(crcToken.getBytes("UTF-8")));
            Map<String, String> map = new HashMap<>();
            map.put("response_token", "sha256=" + hash);
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    @Override
    public void handleTwitterWebhook(TwitterWebhookDto dto) {
        var events = dto.getEvents();
        var users = dto.getUsers();

        if (events == null || events.isEmpty() || users == null || users.isEmpty())
            return;

        for (var event : events)
            processTwitterEvent(event, users);
    }

    private void processTwitterEvent(TwitterWebhookDtoEvent event, Map<String, TwitterWebhookDtoUser> users) {
        String type = event.getType();
        if (!type.equals("message_create"))
            return;

        var messageCreate = event.getMessageCreate();

        String clientId = messageCreate.getSenderId();
        if (clientId.equals(TWITTER_ACCOUNT_ID)) return;
        String conversationId = "TW_" + clientId;
        String mId = event.getId();
        long timestamp = Long.parseLong(event.getCreatedTimestamp());
        LocalDateTime lastMessageDate = LocalDateTime.ofInstant(ofEpochMilli(timestamp), getDefault().toZoneId());

        boolean doesExist = conversationRepository.existsById(conversationId);

        if (doesExist) {
            saveTwitterMessage(conversationId, lastMessageDate, messageCreate, mId);
        } else {
            Platform platform = Platform.TWITTER;
            String clientName = users.get(clientId).getName();
            String lastMessagePreview = messageCreate.isTextMessage() ? messageCreate.getMessageData().getText() : "";
            var conversation = new Conversation(conversationId, platform, clientName, lastMessageDate, truncate(lastMessagePreview));
            createTwitterConversation(conversation, messageCreate, mId);
        }
    }

    private void saveTwitterMessage(String conversationId, LocalDateTime lastMessageDate, TwitterWebhookDtoMessageCreate messageCreate, String mId) {
        boolean sentByClient = true;
        String text = null;
        List<MongoDbMessagesAttachment> attachments = null;

        if (messageCreate.isTextMessage()) text = messageCreate.getMessageData().getText();
        if (messageCreate.hasAttachment()) {
            attachments = new ArrayList<>();
            var messageAttachment = messageCreate.getAttachment();
            String type = messageAttachment.getMedia().getType();
            String url = messageAttachment.getMedia().getUrl();
            attachments.add(new MongoDbMessagesAttachment(type, url));
        }

        var message = new MongoDbMessagesItem(mId, lastMessageDate, sentByClient, text, attachments);
        var webSocketClientMessage = new WebSocketClientMessage(conversationId, message);
        chatController.sendMessage(webSocketClientMessage);
    }

    private void createTwitterConversation(Conversation conversation, TwitterWebhookDtoMessageCreate messageCreate, String mId) {
        boolean sentByClient = true;
        String text = null;
        List<MongoDbMessagesAttachment> attachments = null;

        if (messageCreate.isTextMessage()) text = messageCreate.getMessageData().getText();
        if (messageCreate.hasAttachment()) {
            attachments = new ArrayList<>();
            var messageAttachment = messageCreate.getAttachment();
            String type = messageAttachment.getMedia().getType();
            String url = messageAttachment.getMedia().getUrl();
            attachments.add(new MongoDbMessagesAttachment(type, url));
        }

        LocalDateTime sentDate = conversation.getLastMessageDate();
        var message = new MongoDbMessagesItem(mId, sentDate, sentByClient, text, attachments);
        var webSocketClientConversation = new WebSocketClientConversation(conversation, message);
        chatController.createConversation(webSocketClientConversation);
    }

    private void createInstagramConversation(InstagramConversationDto dto, List<MongoDbMessagesItem> messages) {
        Conversation conversation = new Conversation();
        conversation.setId(dto.getId());
        conversation.setClientName(dto.getClientName());
        conversation.setLastMessageDate(LocalDateTime.parse(dto.getLastMessageDate()));
        conversation.setPlatform(Platform.INSTAGRAM);
        conversation.setLastMessagePreview(truncate(dto.getLastMessageText()));

        WebSocketClientConversation webSocketClientConversation = new WebSocketClientConversation();
        webSocketClientConversation.setConversation(conversation);
        webSocketClientConversation.setMessage(messages.get(0));

        chatController.createConversation(webSocketClientConversation);

        if (messages.size() > 1) {
            messages.remove(0);
            WebSocketClientMessages webSocketClientMessages = new WebSocketClientMessages();
            webSocketClientMessages.setMessages(messages);
            webSocketClientMessages.setConversationId(dto.getId());
            chatController.sendMessages(webSocketClientMessages);
        }

    }

    private void updateInstagramConversation(InstagramConversationDto dto, List<MongoDbMessagesItem> messages) {


        WebSocketClientMessages webSocketClientMessages = new WebSocketClientMessages();
        webSocketClientMessages.setMessages(messages);
        webSocketClientMessages.setConversationId(dto.getId());

        chatController.sendMessages(webSocketClientMessages);
    }


    private void processFacebookEntry(FacebookWebhookDtoEntry entry) {
        var messaging = entry.getMessagingObject();
        var message = messaging.getMessage();
        String clientId = messaging.getSenderId();
        long timestamp = messaging.getTimestamp();
        LocalDateTime lastMessageDate = LocalDateTime.ofInstant(ofEpochMilli(timestamp), getDefault().toZoneId());
        String conversationId = "FB_" + clientId;

        boolean doesExist = conversationRepository.existsById(conversationId);

        if (doesExist) {
            saveFacebookMessage(conversationId, lastMessageDate, message);
        } else {
            Platform platform = Platform.FACEBOOK;
            String clientName = getUserByIdFromFacebook(clientId);
            String lastMessagePreview = message.isTextMessage() ? message.getText() : "";
            var conversation = new Conversation(conversationId, platform, clientName, lastMessageDate, truncate(lastMessagePreview));
            createFacebookConversation(conversation, message);
        }

    }

    private String getUserByIdFromFacebook(String id) {
        try {
            String url = FB_BASE_URL + "/" + id + "?fields=name&access_token=" + FB_PAGE_ACCESS_TOKEN;
            WebClient client = WebClient.create(url);
            Mono<FacebookApiUserDto> mono = client.get().retrieve().bodyToMono(FacebookApiUserDto.class);
            FacebookApiUserDto dto = mono.block();
            return dto.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveFacebookMessage(String id, LocalDateTime lastMessageDate, FacebookWebhookDtoMessage dtoMessage) {
        String mId = dtoMessage.getMid();
        boolean sentByClient = true;
        String text = null;
        List<MongoDbMessagesAttachment> attachments = null;

        if (dtoMessage.isTextMessage()) text = dtoMessage.getText();
        if (dtoMessage.hasAttachment()) {
            attachments = new ArrayList<>();
            var dtoAttachments = dtoMessage.getAttachments();
            for (var item : dtoAttachments) {
                String url = null;
                String type = item.getType();
                var payload = item.getPayload();
                if (payload != null) url = payload.getUrl();
                attachments.add(new MongoDbMessagesAttachment(type, url));
            }
        }

        var message = new MongoDbMessagesItem(mId, lastMessageDate, sentByClient, text, attachments);
        var webSocketClientMessage = new WebSocketClientMessage(id, message);
        chatController.sendMessage(webSocketClientMessage);
    }

    private void createFacebookConversation(Conversation conversation, FacebookWebhookDtoMessage dtoMessage) {
        String mId = dtoMessage.getMid();
        boolean sentByClient = true;
        String text = null;
        List<MongoDbMessagesAttachment> attachments = null;

        if (dtoMessage.isTextMessage()) text = dtoMessage.getText();
        if (dtoMessage.hasAttachment()) {
            var dtoAttachments = dtoMessage.getAttachments();
            for (var item : dtoAttachments) {
                String url = null;
                String type = item.getType();
                var payload = item.getPayload();
                if (payload != null) url = payload.getUrl();
                attachments.add(new MongoDbMessagesAttachment(type, url));
            }
        }

        LocalDateTime sentDate = conversation.getLastMessageDate();
        var message = new MongoDbMessagesItem(mId, sentDate, sentByClient, text, attachments);
        var webSocketClientConversation = new WebSocketClientConversation(conversation, message);
        chatController.createConversation(webSocketClientConversation);
    }

    private void processTelegramMessage(TelegramWebhookDtoMessage message) {
        var from = message.getFrom();
        String clientName = from.getFirstName() + " " + from.getLastName();
        long timestamp = message.getDate() * 1000;
        LocalDateTime lastMessageDate = LocalDateTime.ofInstant(ofEpochMilli(timestamp), getDefault().toZoneId());
        String conversationId = Long.toString(message.getChat().getId());

        boolean doesExist = conversationRepository.existsById(conversationId);

        if (doesExist) {
            saveTelegramMessage(conversationId, lastMessageDate, message);
        } else {
            Platform platform = Platform.TELEGRAM;
            String lastMessagePreview = message.isTextMessage() ? message.getText() : "";
            var conversation = new Conversation(conversationId, platform, clientName, lastMessageDate, truncate(lastMessagePreview));
            createTelegramConversation(conversation, message);
        }
    }

    private void saveTelegramMessage(String conversationId, LocalDateTime lastMessageDate, TelegramWebhookDtoMessage dtoMessage) {
        String mId = Long.toString(dtoMessage.getMessageId());
        boolean sentByClient = true;
        String text = null;
        List<MongoDbMessagesAttachment> attachments = null;

        if (dtoMessage.isTextMessage()) text = dtoMessage.getText();
        if (dtoMessage.hasAttachment()) {
            var attachment = getAttachmentFromTelegram(dtoMessage);
            if (attachment != null) {
                attachments = new ArrayList<>();
                attachments.add(attachment);
            }
        }

        var message = new MongoDbMessagesItem(mId, lastMessageDate, sentByClient, text, attachments);
        var webSocketClientMessage = new WebSocketClientMessage(conversationId, message);
        chatController.sendMessage(webSocketClientMessage);
    }

    private void createTelegramConversation(Conversation conversation, TelegramWebhookDtoMessage dtoMessage) {
        String mId = Long.toString(dtoMessage.getMessageId());
        boolean sentByClient = true;
        String text = null;
        List<MongoDbMessagesAttachment> attachments = null;

        if (dtoMessage.isTextMessage())
            text = dtoMessage.getText();
        if (dtoMessage.hasAttachment()) {
            var attachment = getAttachmentFromTelegram(dtoMessage);
            if (attachment != null) {
                attachments = new ArrayList<>();
                attachments.add(attachment);
            }
        }

        LocalDateTime sentDate = conversation.getLastMessageDate();
        var message = new MongoDbMessagesItem(mId, sentDate, sentByClient, text, attachments);
        var webSocketClientConversation = new WebSocketClientConversation(conversation, message);
        chatController.createConversation(webSocketClientConversation);
    }

    private MongoDbMessagesAttachment getAttachmentFromTelegram(TelegramWebhookDtoMessage dtoMessage) {
        try {
            var photos = dtoMessage.getPhoto();
            var video = dtoMessage.getVideo();
            var voice = dtoMessage.getVoice();
            var document = dtoMessage.getDocument();
            String fileId = null;
            String fileType = null;

            if (photos != null && photos.size() > 0) {
                fileId = photos.get(photos.size() - 1).getFileId();
                fileType = "image";
            } else if (video != null) {
                fileId = video.getFileId();
                fileType = "audio";
            } else if (voice != null) {
                fileId = voice.getFileId();
                fileType = "video";
            } else if (document != null) {
                fileId = document.getFileId();
                fileType = "file";
            }

            if (fileId == null)
                return null;

            String url = TELEGRAM_BASE_URL + "/bot" + TELEGRAM_TOKEN + "/getFile?file_id=" + fileId;
            WebClient client = WebClient.create(url);
            Mono<TelegramApiFilePathDto> mono = client.get().retrieve().bodyToMono(TelegramApiFilePathDto.class);
            var dto = mono.block();
            var result = dto.getResult();
            if (!dto.isOk() || result == null)
                return null;
            String filePath = result.getFilePath();
            String fileUrl = TELEGRAM_BASE_URL + "/bot" + TELEGRAM_TOKEN + "/" + filePath;
            return new MongoDbMessagesAttachment(fileType, fileUrl);

        } catch (Exception e) {
            return null;
        }
    }
}
