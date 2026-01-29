package ai.mindvex.backend.security;

import ai.mindvex.backend.dto.OAuth2UserInfo;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo userInfo = OAuth2UserInfo.fromGitHub(oauth2User.getAttributes());

        // Find or create user
        User user = processOAuth2User(userInfo);

        return new CustomOAuth2User(oauth2User, user);
    }

    private User processOAuth2User(OAuth2UserInfo userInfo) {
        // Check if user exists by provider and provider ID
        Optional<User> userOptional = userRepository
                .findByProviderAndProviderId(userInfo.getProvider(), userInfo.getId());

        if (userOptional.isPresent()) {
            // Update existing user
            User user = userOptional.get();
            user.setFullName(userInfo.getName());
            user.setAvatarUrl(userInfo.getAvatarUrl());
            return userRepository.save(user);
        }

        // Check if user exists by email
        userOptional = userRepository.findByEmail(userInfo.getEmail());
        if (userOptional.isPresent()) {
            // Link OAuth account to existing user
            User user = userOptional.get();
            user.setProvider(userInfo.getProvider());
            user.setProviderId(userInfo.getId());
            user.setAvatarUrl(userInfo.getAvatarUrl());
            return userRepository.save(user);
        }

        // Create new user
        User newUser = User.builder()
                .email(userInfo.getEmail())
                .fullName(userInfo.getName())
                .provider(userInfo.getProvider())
                .providerId(userInfo.getId())
                .avatarUrl(userInfo.getAvatarUrl())
                .build();

        return userRepository.save(newUser);
    }
}
