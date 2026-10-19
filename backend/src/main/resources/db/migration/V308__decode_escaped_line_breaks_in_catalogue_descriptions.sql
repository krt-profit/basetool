UPDATE material
SET description = replace(description, E'\\n', E'\n')
WHERE strpos(description, E'\\n') > 0;

UPDATE manufacturer
SET description = replace(description, E'\\n', E'\n')
WHERE strpos(description, E'\\n') > 0;

UPDATE game_item
SET description_en = replace(description_en, E'\\n', E'\n'),
    description_de = replace(description_de, E'\\n', E'\n')
WHERE strpos(description_en, E'\\n') > 0
   OR strpos(description_de, E'\\n') > 0;

UPDATE ship_type
SET description_en = replace(description_en, E'\\n', E'\n'),
    description_de = replace(description_de, E'\\n', E'\n')
WHERE strpos(description_en, E'\\n') > 0
   OR strpos(description_de, E'\\n') > 0;
