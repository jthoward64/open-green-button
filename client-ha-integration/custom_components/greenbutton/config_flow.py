"""Config flow for Open Green Button.

Stub: full implementation lands in Phase 3 of the project roadmap.
"""

from __future__ import annotations

from homeassistant.config_entries import ConfigFlow

from .const import DOMAIN


class GreenButtonConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Open Green Button."""

    VERSION = 1

    async def async_step_user(self, user_input=None):
        """Phase 0 placeholder — real flow in Phase 3."""
        return self.async_abort(reason="not_implemented")
