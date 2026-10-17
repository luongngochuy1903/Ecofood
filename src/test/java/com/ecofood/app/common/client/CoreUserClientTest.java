package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.dto.UserCreateRequest;
import com.ecofood.app.common.dto.UserResponse;
import com.ecofood.app.common.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreUserClientTest {

  @Mock private UserService userService;

  @Test
  void createUserDelegatesToUserService() {
    CoreUserClient client = new CoreUserClient(userService);
    UserCreateRequest request = new UserCreateRequest();
    UserResponse expected = UserResponse.builder().userId("u1").build();
    when(userService.createUser(request)).thenReturn(expected);

    assertThat(client.createUser(request)).isSameAs(expected);
  }
}
