package az.edu.itbrains.SmartScore.services;

import az.edu.itbrains.SmartScore.models.User;

public interface UserService {
    User findByTelegramChatId(Long chatId);
    boolean linkTelegram(String email, Long chatId);

    User getCurrentUser();
}
