package az.youtube.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UploadPlanTest {

    private static final long MIB = 1024 * 1024;
    private static final long GIB = 1024 * MIB;

    @Test
    void twoGigabyteVideoIsCutIntoSixtyFourMegabyteParts() {
        UploadPlan plan = UploadPlan.of(2 * GIB, 64 * MIB);

        assertThat(plan.partSizeBytes()).isEqualTo(64 * MIB);
        assertThat(plan.partCount()).isEqualTo(32);
    }

    @Test
    void lastPartHoldsTheRemainder() {
        UploadPlan plan = UploadPlan.of(11 * MIB, 5 * MIB);

        assertThat(plan.partCount()).isEqualTo(3);
        assertThat(plan.expectedPartSize(1, 11 * MIB)).isEqualTo(5 * MIB);
        assertThat(plan.expectedPartSize(3, 11 * MIB)).isEqualTo(MIB);
    }

    @Test
    void partSizeNeverGoesBelowTheS3Minimum() {
        assertThat(UploadPlan.of(100 * MIB, MIB).partSizeBytes()).isEqualTo(5 * MIB);
    }

    @Test
    void partSizeGrowsSoTheFileFitsIntoTenThousandParts() {
        UploadPlan plan = UploadPlan.of(1000 * GIB, 64 * MIB);

        assertThat(plan.partCount()).isLessThanOrEqualTo(10_000);
        assertThat(plan.partSizeBytes() * plan.partCount()).isGreaterThanOrEqualTo(1000 * GIB);
    }

    @Test
    void smallFileIsOnePart() {
        UploadPlan plan = UploadPlan.of(1000, 64 * MIB);

        assertThat(plan.partCount()).isEqualTo(1);
        assertThat(plan.expectedPartSize(1, 1000)).isEqualTo(1000);
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> UploadPlan.of(0, 64 * MIB)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UploadPlan.of(60_000 * GIB, 64 * MIB)).isInstanceOf(IllegalArgumentException.class);
    }
}
