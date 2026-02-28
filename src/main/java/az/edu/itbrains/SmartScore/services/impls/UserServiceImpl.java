package az.edu.itbrains.SmartScore.services.impls;

import az.edu.itbrains.SmartScore.models.User;
import az.edu.itbrains.SmartScore.repositories.UserRepository;
import az.edu.itbrains.SmartScore.services.UserService;
import lombok.RequiredArgsConstructor;
//import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public User findByTelegramChatId(Long chatId) {
        return userRepository.findByTelegramChatId(chatId).orElse(null);
    }

    @Override
    public boolean linkTelegram(String email, Long chatId) {
        User user = userRepository.findByEmail(email);
        if (user != null) {
            user.setTelegramChatId(chatId);
            userRepository.save(user);
            return true;
        }
        return false;
    }

    @Override
    public User getCurrentUser() {
        String email = "test@mail.com";

        User user = userRepository.findByEmail(email);
        if (user == null) {
            user = new User();
            user.setEmail(email);
            user.setFullName("Test User");

            user.setPassword("password_stub");

            user = userRepository.save(user);
        }
        return user;
    }
}