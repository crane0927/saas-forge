import { useEffect, useState } from 'react';

export interface RouteFocusAnnouncementProps {
  readonly routeKey: string;
  readonly pageTitle: string;
  readonly focusTargetId: string;
}

/** 路由内容提交后聚焦页面主标题，并以非阻塞状态通知读屏软件。 */
export function RouteFocusAnnouncement({
  routeKey,
  pageTitle,
  focusTargetId,
}: RouteFocusAnnouncementProps) {
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    document.getElementById(focusTargetId)?.focus();
    setAnnouncement(pageTitle);
  }, [focusTargetId, pageTitle, routeKey]);

  return (
    <p className="sf-visually-hidden" role="status" aria-live="polite" aria-atomic="true">
      {announcement}
    </p>
  );
}
