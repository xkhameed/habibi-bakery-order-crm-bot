package uz.habibi.bakery.service;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import uz.habibi.bakery.domain.DeliveryType;
import uz.habibi.bakery.domain.PaymentMethod;

public record CreateOrderRequest(
        @NotEmpty(message = "Savat bo'sh") @Size(max = 50) List<@Valid Item> items,
        @NotBlank(message = "Ismingizni kiriting") @Size(max = 100) String customerName,
        @NotBlank(message = "Telefon raqamni kiriting")
        @Pattern(regexp = "^\\+?[0-9 ()-]{9,20}$", message = "Telefon raqam noto'g'ri") String phone,
        @NotNull DeliveryType deliveryType,
        @Size(max = 500) String address,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude,
        /** Ixtiyoriy: berilmasa — bugun, «iloji boricha tezroq». */
        LocalDate deliveryDate,
        String deliveryTime,
        @NotNull(message = "To'lov usulini tanlang") PaymentMethod paymentMethod,
        @Size(max = 500) String comment,
        /** uz | cyr | ru — mijoz tanlagan til */
        String language,
        /** Tortga yozuv (ixtiyoriy) */
        @Size(max = 150) String inscription) {

    public record Item(@NotNull Long variantId, @Min(1) @Max(50) int quantity) {
    }
}
