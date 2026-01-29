package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuth2UserInfo {
    private String id;
    private String email;
    private String name;
    private String avatarUrl;
    private String provider;

    public static OAuth2UserInfo fromGitHub(Map<String, Object> attributes) {
        return OAuth2UserInfo.builder()
                .id(String.valueOf(attributes.get("id")))
                .email((String) attributes.get("email"))
                .name((String) attributes.get("name"))
                .avatarUrl((String) attributes.get("avatar_url"))
                .provider("github")
                .build();
    }
}
