package com.ecofood.app.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.service.UserQueryService;
import com.ecofood.app.userACL.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreUserQueryClientTest {

  @Mock private UserQueryService userQueryService;

  @Test
  void getByIdDelegatesToUserQueryService() {
    CoreUserQueryClient client = new CoreUserQueryClient(userQueryService);
    UserEntity expected = UserEntity.builder().username("jane.doe").build();
    when(userQueryService.getById("u1")).thenReturn(expected);

    assertThat(client.getById("u1")).isSameAs(expected);
  }

  @Test
  void getByUsernameDelegatesToUserQueryService() {
    CoreUserQueryClient client = new CoreUserQueryClient(userQueryService);
    UserEntity expected = UserEntity.builder().username("jane.doe").build();
    when(userQueryService.getByUsername("jane.doe")).thenReturn(expected);

    assertThat(client.getByUsername("jane.doe")).isSameAs(expected);
  }
}
