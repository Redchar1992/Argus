"""Character-card contracts and mechanical business validation."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

CharacterRole = Literal["主角", "反派", "盟友", "关键配角", "辅助角色"]


class CharacterCard(BaseModel):
    """One short-drama character with an explicit dramatic function."""

    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    name: str = Field(min_length=1, max_length=20)
    role: CharacterRole
    public_identity: str = Field(min_length=2, max_length=120)
    hidden_secret: str = Field(min_length=2, max_length=240)
    core_desire: str = Field(min_length=2, max_length=160)
    greatest_fear: str = Field(min_length=2, max_length=160)
    personality: list[str] = Field(min_length=2, max_length=5)
    relationship_to_protagonist: str = Field(min_length=1, max_length=160)
    character_arc: str = Field(min_length=4, max_length=320)


class CharacterPack(BaseModel):
    """A deliberately small cast for the MVP workflow."""

    model_config = ConfigDict(extra="forbid")

    characters: list[CharacterCard] = Field(min_length=3, max_length=6)

    @model_validator(mode="after")
    def validate_cast(self) -> CharacterPack:
        names = [character.name for character in self.characters]
        if len(set(names)) != len(names):
            raise ValueError("人物姓名不得重复")

        roles = [character.role for character in self.characters]
        if roles.count("主角") != 1:
            raise ValueError("人物包必须且只能包含一名主角")
        if "反派" not in roles:
            raise ValueError("人物包必须包含至少一名反派")
        return self
