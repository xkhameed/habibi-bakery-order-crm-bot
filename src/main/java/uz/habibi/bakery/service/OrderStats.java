package uz.habibi.bakery.service;

import java.util.List;

/** Admin statistikasi. Hamma raqamlar bazada yig'ma so'rov bilan hisoblanadi. */
public record OrderStats(Period today, Period month, long activeCount, List<TopProduct> topProducts) {

    /**
     * @param orders    bekor qilinmagan buyurtmalar soni
     * @param cancelled bekor qilinganlar soni
     * @param revenue   bekor qilinmaganlar summasi
     */
    public record Period(long orders, long cancelled, long revenue) {
        public long averageCheck() {
            return orders == 0 ? 0 : revenue / orders;
        }

        /** Bekor qilinganlar ulushi, foizda. */
        public long cancelRate() {
            long all = orders + cancelled;
            return all == 0 ? 0 : Math.round(cancelled * 100.0 / all);
        }
    }

    public record TopProduct(String name, long quantity) {
    }

    /**
     * Tanlangan davr hisoboti.
     * @param total     jami buyurtmalar (bekorlar bilan)
     * @param done      bekor qilinmaganlar
     * @param revenue   bekor qilinmaganlar summasi
     * @param paid      to'lovi tasdiqlanganlar summasi
     */
    public record Report(long total, long done, long cancelled, long revenue, long paid, long paidCount,
                         List<TopProduct> top) {
        public long averageCheck() {
            return done == 0 ? 0 : revenue / done;
        }

        public long cancelRate() {
            return total == 0 ? 0 : Math.round(cancelled * 100.0 / total);
        }
    }
}
