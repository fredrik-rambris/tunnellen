package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterSocksTunnelTest {

    @Test
    void slugifyUsernameLowercasesAndKeepsSimpleNames() {
        assertEquals("fredrik", ClusterSocksTunnel.slugifyUsername("fredrik"));
        assertEquals("fredrik", ClusterSocksTunnel.slugifyUsername("Fredrik"));
    }

    @Test
    void slugifyUsernameCollapsesInvalidCharsToSingleDash() {
        assertEquals("john-doe", ClusterSocksTunnel.slugifyUsername("john.doe"));
        assertEquals("a-b-c", ClusterSocksTunnel.slugifyUsername("a_b/c"));
        assertEquals("f-o-o", ClusterSocksTunnel.slugifyUsername("f!!o??o"));
    }

    @Test
    void slugifyUsernameTrimsLeadingAndTrailingDashes() {
        assertEquals("foo", ClusterSocksTunnel.slugifyUsername(".foo."));
        assertEquals("foo", ClusterSocksTunnel.slugifyUsername("---foo---"));
    }

    @Test
    void slugifyUsernameFallsBackWhenNothingUsableRemains() {
        assertEquals("user", ClusterSocksTunnel.slugifyUsername("???"));
        assertEquals("user", ClusterSocksTunnel.slugifyUsername(""));
    }

    @Test
    void differentUsernamesProduceDifferentSlugs() {
        // Regression test for the multi-developer collision: the pod name
        // (tunnellen-socks-<slug>) is derived from this slug, so two
        // developers on the same context must not collide here.
        assertEquals("fredrik", ClusterSocksTunnel.slugifyUsername("fredrik"));
        assertEquals("jane", ClusterSocksTunnel.slugifyUsername("jane"));
    }
}
