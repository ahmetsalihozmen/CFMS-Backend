package tr.edu.yildiz.cfms.api.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import tr.edu.yildiz.cfms.api.dtos.webhooks.facebook.FacebookWebhookDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.instagram.InstagramConversationDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.telegram.TelegramWebhookDto;
import tr.edu.yildiz.cfms.api.dtos.webhooks.telegram.TelegramWebhookDtoFrom;
import tr.edu.yildiz.cfms.business.abstracts.WebhookService;
import tr.edu.yildiz.cfms.core.response_types.Response;
import tr.edu.yildiz.cfms.core.response_types.SuccessResponse;
import tr.edu.yildiz.cfms.core.utils.ExternalApiClients;

import java.io.IOException;

import static tr.edu.yildiz.cfms.core.utils.Constants.TELEGRAM_TOKEN;


@RestController
@RequestMapping("/api/webhooks")
public class WebhooksController {
    @Autowired
    private WebhookService webhookService;


    private static final String VERIFY_TOKEN = "EAAE0ZBjxLFEQBACbTM5ZAzLWYECXWuu4rlSo8QQRzJQv551FQIQtNjxWAEBvShjZCOCd4SIOQGdyDhKjSnGfZArOC1z6rDf4B7OaOG9Ubsg6VGOZAnr8XsODooZCVZCA2I7LvIpPnfApgknn3Rod3RoqJHF7nX30F3ubbAXzA7nur2RuZCVT2KeZBKThBguxAOTYZD";

    @PostMapping("/facebook")
    public Response handleFacebookWebhook(@RequestBody FacebookWebhookDto dto) {
        webhookService.handleFacebookWebhook(dto);
        return new SuccessResponse();
    }

    @GetMapping("/facebook")
    public String verifyFacebookWebhook(@RequestParam("hub.verify_token") String token, @RequestParam("hub.challenge") String challenge) {
        if (token != null && token.equals(VERIFY_TOKEN)) return challenge;
        return "Not verified!";
    }

    @PostMapping("/telegram")
    public Response handleTelegramWebhook(@RequestBody TelegramWebhookDto dto) {
        webhookService.handleTelegramWebhook(dto);
        return new SuccessResponse();
    }

    @GetMapping("/telegram")
    public Response verifyTelegramWebhook() {
        return new SuccessResponse();
    }

    @PostMapping("/newinstagram")
    public Response verifyInstagramWebhook(@RequestBody InstagramConversationDto dto) throws IOException {
        webhookService.handleInstagramConversation(dto);
        return new SuccessResponse();
    }
}
