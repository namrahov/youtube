package az.youtube.controller;

import az.youtube.dto.request.ViewRequest;
import az.youtube.security.CurrentUser;
import az.youtube.views.ViewService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ViewController {

    private final ViewService views;

    public ViewController(ViewService views) {
        this.views = views;
    }

    /**
     * Sent by the player once a view has been watched long enough. 202: accepted for counting; the count shown on the
     * video catches up within seconds (eventual consistency).
     */
    @PostMapping("/api/videos/{videoId}/views")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void view(@CurrentUser long userId, @PathVariable long videoId, @Valid @RequestBody ViewRequest request) {
        views.record(userId, videoId, request);
    }
}
