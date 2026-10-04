package com.seatres.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class OpenApiConfig implements WebMvcConfigurer {

    static final String SWAGGER_UI_PATH = "/swagger-ui.html";
    static final String BEARER_SCHEME = "bearerAuth";

    private static final String DESCRIPTION = """
            Book specific seats for a show, the way a cinema or theatre booking page does.

            **What you can do**
            - Look at a show and see every seat: its price, and whether it is still free.
            - Book one or more seats in a single request. You get all of them or none at all,
            never a partial booking.
            - Give seats back by cancelling your booking, which makes them available to everyone
            again.

            **How it behaves when a show is in demand**
            - If two people reach for the same seat at the same moment, exactly one of them gets
            it and the other is told it has gone.
            - One person can hold only a few seats per show, so a single account cannot take the
            whole house.
            - If your connection drops and you send the same booking again, you will not end up
            with two bookings. Retrying with the same `Idempotency-Key` is always safe.

            **Try it**
            1. `POST /auth/token` with `{\"sub\":\"alice\"}` and copy `accessToken` from the response.
            2. Click **Authorize** at the top and paste the token.
            3. You need a show to book against. With the admin key, get an ADMIN token the same way
            (add the `X-Admin-Key` header and ask for `roles: [\"ADMIN\"]`), create a show with
            `POST /shows`, and note the `id` it returns. Without the key, ask whoever runs this
            service for a show id.
            4. `GET /shows/{showId}` shows the seat map, `POST /shows/{showId}/reserve` books seats
            from it, and `POST /reservations/{reservationId}/cancel` gives them back.

            **Metrics and health** are public and need no token: `/actuator/prometheus` for the
            Prometheus scrape (including seats per show), `/actuator/health`, `/livez` and
            `/readyz`. Logs are written to stdout in ECS JSON and are read from the host's console,
            so they are not reachable over HTTP; the full captured history is committed under
            `docs/evidence/`.

            The admin key is not published here; ask whoever runs this service if you need it.
            This is a demonstration service with a stand-in
            login: anyone can get a token for any name, so treat everything in it as throwaway
            test data.""";

    @Bean
    OpenAPI seatReservationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Seat Reservation Service")
                        .version("1.0.0")
                        .description(DESCRIPTION))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("An HS256 token from POST /auth/token.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /** So the bare deployed URL lands somewhere usable instead of a 401. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", SWAGGER_UI_PATH);
    }
}
