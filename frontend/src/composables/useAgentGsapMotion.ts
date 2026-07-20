import { nextTick, onMounted, onUnmounted, type Ref } from 'vue';
import { gsap } from 'gsap';

type AgentMotionOptions = {
  root: Ref<HTMLElement | null>;
  chatLog: Ref<HTMLElement | null>;
};

export function useAgentGsapMotion(options: AgentMotionOptions) {
  let mm: gsap.MatchMedia | undefined;
  let sidebarTween: gsap.core.Tween | undefined;
  let railTween: gsap.core.Tween | undefined;
  let reduceMotionQuery: MediaQueryList | undefined;

  function prefersReducedMotion() {
    reduceMotionQuery ||= window.matchMedia('(prefers-reduced-motion: reduce)');
    return reduceMotionQuery.matches;
  }

  function scopedElement<T extends Element = HTMLElement>(selector: string) {
    return options.root.value?.querySelector<T>(selector) || null;
  }

  function scopedElements<T extends Element = HTMLElement>(selector: string) {
    return Array.from(options.root.value?.querySelectorAll<T>(selector) || []);
  }

  onMounted(() => {
    const root = options.root.value;
    if (!root) return;
    mm = gsap.matchMedia();
    mm.add({
      isDesktop: '(min-width: 900px)',
      isMobile: '(max-width: 899px)',
      reduceMotion: '(prefers-reduced-motion: reduce)'
    }, (context) => {
      const { isDesktop, isMobile, reduceMotion } = context.conditions || {};
      const studioNav = scopedElement<HTMLElement>('.studio-nav');
      const leftSidebar = scopedElement<HTMLElement>('.runtime-left-sidebar');
      const heading = scopedElement<HTMLElement>('.studio-heading');
      const worktopActions = scopedElements<HTMLElement>('.runtime-mode-switch, .runtime-model-pill, .runtime-worktop-actions > *');
      const chat = scopedElement<HTMLElement>('.stitch-chat');
      const composer = scopedElement<HTMLElement>('.stitch-composer');
      const inspector = scopedElement<HTMLElement>('.runtime-inspector');
      const introTargets = [studioNav, leftSidebar, heading, ...worktopActions, chat, composer, inspector]
        .filter((target): target is HTMLElement => target != null);
      if (reduceMotion) {
        gsap.set(introTargets, { clearProps: 'all' });
        return;
      }
      gsap.defaults({ ease: 'power3.out' });
      gsap.set(introTargets, {
        willChange: 'transform, opacity'
      });
      const timeline = gsap.timeline({ defaults: { duration: isMobile ? 0.28 : 0.42 } });
      if (studioNav) timeline.from(studioNav, { autoAlpha: 0, y: isMobile ? -8 : -14 });
      if (heading) timeline.from(heading, { autoAlpha: 0, y: isMobile ? 8 : 14, scale: 0.992 }, '<0.04');
      if (worktopActions.length) timeline.from(worktopActions, { autoAlpha: 0, y: 10, stagger: 0.04 }, '<0.04');
      if (chat) timeline.from(chat, { autoAlpha: 0, y: isMobile ? 8 : 14 }, '<0.04');
      if (composer) timeline.from(composer, { autoAlpha: 0, y: isMobile ? 8 : 14, scale: 0.995 }, '<0.02');

      if (isDesktop) {
        if (leftSidebar) timeline.from(leftSidebar, { autoAlpha: 0, x: -14 }, '<0.05');
        if (inspector) timeline.from(inspector, { autoAlpha: 0, x: 14 }, '<0.02');
      }

      return () => {
        gsap.killTweensOf(scopedElements('*'));
        gsap.set(introTargets, { clearProps: 'willChange' });
      };
    }, root);
  });

  onUnmounted(() => {
    sidebarTween?.kill();
    railTween?.kill();
    gsap.killTweensOf(scopedElements('*'));
    mm?.revert();
  });

  async function revealLastMessage() {
    await nextTick();
    if (prefersReducedMotion()) return;
    const messages = Array.from(options.chatLog.value?.querySelectorAll<HTMLElement>('.message') || []);
    const target = messages[messages.length - 1];
    if (!target) return;
    gsap.fromTo(target,
      { autoAlpha: 0, y: 14, scale: 0.992 },
      { autoAlpha: 1, y: 0, scale: 1, duration: 0.32, ease: 'power3.out', overwrite: 'auto' }
    );
  }

  async function revealDecision() {
    await revealCard('.agent-decision-pill', { y: 10, scale: 0.985 });
  }

  async function revealActionCard() {
    await revealCard('.task-approval-card, .action-required-card', { y: 14, scale: 0.975 });
  }

  async function revealTaskStatus() {
    await revealCard('.task-status-card', { y: 10, scale: 0.985 });
  }

  async function revealTrace() {
    await nextTick();
    if (prefersReducedMotion()) return;
    const strip = scopedElement<HTMLElement>('.run-trace-strip');
    const rows = scopedElements<HTMLElement>('.trace-row');
    const latestRow = rows[rows.length - 1];
    const timeline = gsap.timeline({ defaults: { duration: 0.26, ease: 'power3.out', overwrite: 'auto' } });
    if (strip) {
      timeline.fromTo(strip, { autoAlpha: 0.2, y: 8, scale: 0.988 }, { autoAlpha: 1, y: 0, scale: 1 });
    }
    if (latestRow) {
      timeline.fromTo(latestRow, { autoAlpha: 0, x: 12 }, { autoAlpha: 1, x: 0 }, '<0.04');
    }
  }

  async function revealInspectorPanel() {
    await nextTick();
    if (prefersReducedMotion()) return;
    const panel = scopedElement<HTMLElement>('.inspector-panel');
    if (!panel) return;
    gsap.fromTo(panel,
      { autoAlpha: 0, x: 8 },
      { autoAlpha: 1, x: 0, duration: 0.22, ease: 'power2.out', overwrite: 'auto' }
    );
  }

  async function nudgeComposer() {
    await nextTick();
    if (prefersReducedMotion()) return;
    const composer = scopedElement<HTMLElement>('.stitch-composer');
    if (!composer) return;
    gsap.fromTo(composer,
      { scale: 0.992 },
      { scale: 1, duration: 0.26, ease: 'power2.out', overwrite: 'auto' }
    );
  }

  function animateSidebar(open: boolean, pinned: boolean) {
    if (prefersReducedMotion() || window.matchMedia('(max-width: 1100px)').matches) return;
    const drawer = scopedElement<HTMLElement>('.sidebar-drawer');
    const rail = scopedElement<HTMLElement>('.sidebar-rail');
    if (!drawer || !rail) return;

    sidebarTween?.kill();
    railTween?.kill();
    sidebarTween = gsap.to(drawer, {
      autoAlpha: open ? 1 : 0,
      x: open ? 0 : 22,
      scale: open ? 1 : 0.985,
      duration: open ? 0.3 : 0.2,
      ease: open ? 'power3.out' : 'power2.in',
      overwrite: 'auto'
    });
    railTween = gsap.to(rail, {
      autoAlpha: pinned ? 0 : open ? 0.2 : 1,
      x: pinned ? 18 : open ? -2 : 0,
      duration: 0.2,
      ease: 'power2.out',
      overwrite: 'auto'
    });
  }

  async function revealCard(selector: string, from: gsap.TweenVars) {
    await nextTick();
    if (prefersReducedMotion()) return;
    const target = scopedElement<HTMLElement>(selector);
    if (!target) return;
    gsap.fromTo(target,
      { autoAlpha: 0, ...from },
      { autoAlpha: 1, y: 0, x: 0, scale: 1, duration: 0.34, ease: 'power3.out', overwrite: 'auto' }
    );
  }

  return {
    animateSidebar,
    nudgeComposer,
    revealActionCard,
    revealDecision,
    revealInspectorPanel,
    revealLastMessage,
    revealTaskStatus,
    revealTrace
  };
}
