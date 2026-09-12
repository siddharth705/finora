package com.finora.integrations.setu;

import com.finora.exception.ApiException;
import com.finora.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorLinkControllerTest {

    private SetuConsentService consentService;
    private CurrentUser currentUser;
    private AccountAggregatorIdentityResolutionService identityResolutionService;
    private AccountAggregatorLinkController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consentService = mock(SetuConsentService.class);
        currentUser = mock(CurrentUser.class);
        identityResolutionService = mock(AccountAggregatorIdentityResolutionService.class);
        when(currentUser.id()).thenReturn(userId);
        controller = new AccountAggregatorLinkController(consentService, currentUser, identityResolutionService);
    }

    @Test
    void initiateReturnsTheLinkIdStatusAndRedirectUrl() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        SetuConsentService.InitiateLinkResult result =
                new SetuConsentService.InitiateLinkResult(link, "https://aa.example/redirect");
        when(consentService.initiateLink(userId, FiType.DEPOSIT, "idem-1")).thenReturn(result);

        AccountAggregatorLinkController.InitiateLinkRequest request =
                new AccountAggregatorLinkController.InitiateLinkRequest(FiType.DEPOSIT, "idem-1");
        AccountAggregatorLinkController.InitiateLinkResponse response = controller.initiate(request).getBody();

        assertThat(response.status()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(response.redirectUrl()).isEqualTo("https://aa.example/redirect");
    }

    @Test
    void confirmExistingAccountDelegatesToTheServiceWithTheCallingUser() {
        UUID linkId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        controller.confirmExistingAccount(linkId,
                new AccountAggregatorLinkController.ConfirmExistingAccountRequest(accountId));

        org.mockito.Mockito.verify(identityResolutionService).confirmExistingAccount(userId, linkId, accountId);
    }

    @Test
    void confirmExistingAccountPropagatesAnOwnershipRejectionFromTheService() {
        UUID linkId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "not yours"))
                .when(identityResolutionService).confirmExistingAccount(userId, linkId, accountId);

        assertThatThrownBy(() -> controller.confirmExistingAccount(linkId,
                new AccountAggregatorLinkController.ConfirmExistingAccountRequest(accountId)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void confirmNewAccountDelegatesToTheServiceWithTheCallingUser() {
        UUID linkId = UUID.randomUUID();

        controller.confirmNewAccount(linkId);

        org.mockito.Mockito.verify(identityResolutionService).confirmNewAccount(userId, linkId);
    }
}
