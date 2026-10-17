package com.ecofood.app.userACL.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceImplTest {

  @Mock private UserRepository userRepository;

  private UserQueryServiceImpl userQueryService;

  @Test
  void getByIdReturnsTheEntityWhenFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    ObjectId id = new ObjectId();
    user.setId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThat(userQueryService.getById(id.toHexString()).getUsername()).isEqualTo("jane.doe");
  }

  @Test
  void getByIdThrowsWhenNotFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    ObjectId id = new ObjectId();
    when(userRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userQueryService.getById(id.toHexString())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void getByUsernameReturnsTheEntityWhenFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    UserEntity user = UserEntity.builder().username("jane.doe").build();
    when(userRepository.findByUsername("jane.doe")).thenReturn(Optional.of(user));

    assertThat(userQueryService.getByUsername("jane.doe").getUsername()).isEqualTo("jane.doe");
  }

  @Test
  void getByUsernameThrowsWhenNotFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userQueryService.getByUsername("ghost")).isInstanceOf(IllegalArgumentException.class);
  }
}
