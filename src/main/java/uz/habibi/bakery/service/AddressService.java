package uz.habibi.bakery.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.domain.CustomerAddress;
import uz.habibi.bakery.repo.CustomerAddressRepository;

/** «Mening manzillarim»: har mijozga 3 tagacha saqlangan manzil. */
@Service
public class AddressService {

    public static final int MAX = 3;

    private final CustomerAddressRepository addresses;

    public AddressService(CustomerAddressRepository addresses) {
        this.addresses = addresses;
    }

    public record AddressView(Long id, String label, double latitude, double longitude, String mapLabel, String address) {
        static AddressView of(CustomerAddress a) {
            return new AddressView(a.getId(), a.getLabel(), a.getLatitude(), a.getLongitude(), a.getMapLabel(), a.getAddress());
        }
    }

    public record AddressRequest(String label, Double latitude, Double longitude, String mapLabel, String address) {
    }

    @Transactional(readOnly = true)
    public List<AddressView> list(long telegramId) {
        return addresses.findAllByTelegramIdOrderByCreatedAtAsc(telegramId).stream().map(AddressView::of).toList();
    }

    /** id null — yangi, aks holda o'zgartirish (faqat o'z manzilini). */
    @Transactional
    public AddressView save(long telegramId, Long id, AddressRequest req) {
        String label = req.label() == null ? "" : req.label().strip();
        if (label.isEmpty() || label.length() > 40) {
            throw new BusinessException("err.addr_label");
        }
        if (req.latitude() == null || req.longitude() == null
                || Math.abs(req.latitude()) > 90 || Math.abs(req.longitude()) > 180) {
            throw new BusinessException("err.location_required");
        }
        CustomerAddress a;
        if (id == null) {
            if (addresses.countByTelegramId(telegramId) >= MAX) {
                throw new BusinessException("err.addr_max", MAX);
            }
            a = new CustomerAddress(telegramId);
        } else {
            a = addresses.findById(id).filter(x -> x.getTelegramId() == telegramId)
                    .orElseThrow(() -> new BusinessException("err.not_found"));
        }
        a.update(label, req.latitude(), req.longitude(), trim(req.mapLabel(), 300), trim(req.address(), 500));
        return AddressView.of(addresses.save(a));
    }

    @Transactional
    public void delete(long telegramId, Long id) {
        addresses.findById(id).filter(x -> x.getTelegramId() == telegramId).ifPresent(addresses::delete);
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
