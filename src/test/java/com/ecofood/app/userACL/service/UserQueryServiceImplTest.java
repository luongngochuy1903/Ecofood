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
    UserEntity user = UserEntity.builder().username("jane.doe").firstName("Jane").lastName("Doe").build();
    ObjectId id = new ObjectId();
    user.setId(id);
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    var response = userQueryService.getById(id.toHexString());
    assertThat(response.getUsername()).isEqualTo("jane.doe");
    assertThat(response.getFirstName()).isEqualTo("Jane");
    assertThat(response.getLastName()).isEqualTo("Doe");
  }

  @Test
  void getByIdThrowsWhenNotFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    ObjectId id = new ObjectId();
    when(userRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userQueryService.getById(id.toHexString())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void getByEmailReturnsTheEntityWhenFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    UserEntity user = UserEntity.builder().username("jane.doe").email("jane.doe@example.com").build();
    when(userRepository.findByEmailIgnoreCase("jane.doe@example.com")).thenReturn(Optional.of(user));

    assertThat(userQueryService.getByEmail("jane.doe@example.com").getEmail()).isEqualTo("jane.doe@example.com");
  }

  @Test
  void getByEmailThrowsWhenNotFound() {
    userQueryService = new UserQueryServiceImpl(userRepository);
    when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userQueryService.getByEmail("ghost@example.com")).isInstanceOf(IllegalArgumentException.class);
  }
}
