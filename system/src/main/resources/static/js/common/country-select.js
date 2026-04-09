document.addEventListener('DOMContentLoaded', async function () {
    const COUNTRY_API_URL = 'https://restcountries.com/v3.1/all?fields=name';
    const selects = document.querySelectorAll('select[data-country-select]');

    if (!selects.length) {
        return;
    }

    const OTHER_OPTION = 'Khác';
    const PLACEHOLDER_DEFAULT = '-- Chọn quốc tịch --';

    function normalize(value) {
        return (value || '').trim();
    }

    function clearOptions(select) {
        while (select.firstChild) {
            select.removeChild(select.firstChild);
        }
    }

    function appendOption(select, value, text, selected = false) {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = text;
        option.selected = selected;
        select.appendChild(option);
    }

    function buildPlaceholder(select, hasSelectedValue) {
        const placeholder = normalize(select.getAttribute('data-placeholder')) || PLACEHOLDER_DEFAULT;
        appendOption(select, '', placeholder, !hasSelectedValue);
    }

    function getSelectedCountry(select) {
        return normalize(select.getAttribute('data-selected-country'))
            || normalize(select.getAttribute('data-default-country'));
    }

    function populateSelect(select, countries, sourceFailed) {
        const originalSelectedCountry = getSelectedCountry(select);
        const hasCountryInList = originalSelectedCountry && countries.includes(originalSelectedCountry);
        const selectedCountry = hasCountryInList ? originalSelectedCountry : (originalSelectedCountry ? OTHER_OPTION : '');

        clearOptions(select);
        buildPlaceholder(select, !!selectedCountry);

        countries.forEach(function (country) {
            appendOption(select, country, country, country === selectedCountry);
        });

        appendOption(select, OTHER_OPTION, OTHER_OPTION, selectedCountry === OTHER_OPTION);

        if (sourceFailed) {
            select.setAttribute('title', 'Không tải được danh sách quốc gia, vui lòng chọn "Khác" nếu cần.');
        } else {
            select.removeAttribute('title');
        }
    }

    async function fetchCountries() {
        const response = await fetch(COUNTRY_API_URL, {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        });

        if (!response.ok) {
            throw new Error('REST Countries responded with status ' + response.status);
        }

        const data = await response.json();

        const countries = data
            .map(function (item) {
                return normalize(item && item.name && item.name.common);
            })
            .filter(Boolean)
            .filter(function (value, index, array) {
                return array.indexOf(value) === index;
            })
            .filter(function (value) {
                return value !== OTHER_OPTION;
            })
            .sort(function (a, b) {
                return a.localeCompare(b);
            });

        if (!countries.length) {
            throw new Error('Country list is empty');
        }

        return countries;
    }

    let countries = [];
    let sourceFailed = false;

    try {
        countries = await fetchCountries();
    } catch (error) {
        console.error('Cannot load countries from REST Countries:', error);
        countries = [];
        sourceFailed = true;
    }

    selects.forEach(function (select) {
        populateSelect(select, countries, sourceFailed);
    });
});